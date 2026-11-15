package com.courtly.service;

import static com.courtly.util.Rules.*;

import com.courtly.dto.request.PaymentRequest;
import com.courtly.entity.*;
import com.courtly.repository.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Bank notifications are separate from a customer's declaration of transfer. */
@Service
@RequiredArgsConstructor
@Transactional
public class TransferService {
  private final DocumentService docs;
  private final CustomerOrderRepository orders;
  private final TournamentRegistrationRepository registrations;
  private final AccountRepository accounts;
  private final PaymentService payments;
  private final OrderService booking;
  private final DemoAccessPolicy demo;
  // Gửi biên lai điện tử qua email/in-app khi ngân hàng xác nhận tiền về (inject lười để giữ constructor cũ)
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  @org.springframework.context.annotation.Lazy
  private NotificationService notifications;

  @Value("${courtly.payment.mode:MANUAL}")
  private String mode;

  @Value("${courtly.payment.bank-bin:970422}")
  private String bankBin;

  @Value("${courtly.payment.account-number:0345678999}")
  private String accountNumber;

  @Value("${courtly.payment.account-name:CLB TENNIS COURTLY}")
  private String accountName;

  @Value("${courtly.payment.webhook-key:}")
  private String webhookKey;

  @Value("${courtly.admin-email:admin@gmail.com}")
  private String adminEmail;

  public String getMode() {
    return mode == null || mode.isBlank() ? "MANUAL" : ("SEPAY".equalsIgnoreCase(mode) ? "LIVE" : mode.toUpperCase(java.util.Locale.ROOT));
  }

  public String getBankBin() {
    return bankBin != null && bankBin.matches("\\d{6}") ? bankBin : "";
  }

  public String getAccountNumber() {
    return accountNumber != null && !accountNumber.isBlank() ? accountNumber : "";
  }

  public String getAccountName() {
    return accountName != null && !accountName.isBlank() ? accountName : "";
  }

  public Map<String, Object> config() {
    boolean bankConfigured = getBankBin().matches("\\d{6}") && getAccountNumber().matches("\\d{6,30}") && !getAccountName().isBlank();
    boolean isDemo = "DEMO".equals(getMode()) || "SANDBOX".equals(getMode());
    boolean onlineEnabled = ("LIVE".equals(getMode()) && webhookKey != null && !webhookKey.isBlank())
        || (isDemo && bankConfigured);
    return map(
        "mode", getMode(),
        "onlineEnabled", onlineEnabled,
        "bankConfigured", bankConfigured,
        "bankBin", getBankBin(),
        "bankName", "970422".equals(getBankBin()) ? "MB Bank" : getBankBin(),
        "accountNumber", getAccountNumber(),
        "accountName", getAccountName());
  }

  private BigDecimal amount(Object n) {
    var v = PresentationService.decimal(n);
    require(v.signum() > 0 && v.stripTrailingZeros().scale() <= 0, "Số tiền phải là số nguyên VND dương");
    return v;
  }

  private void own(Account a, Object owner) {
    if (owner == null || !a.getId().toString().equals(owner.toString()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Giao dịch không thuộc tài khoản của bạn");
  }

  public List<Map<String, Object>> mine(Account a) {
    return docs.list("transfer:").stream()
        .filter(x -> a.getId().toString().equals(x.get("accountId")))
        .map(this::view)
        .toList();
  }

  public List<Map<String, Object>> all() {
    return docs.list("transfer:").stream().map(this::view).toList();
  }

  private Map<String, Object> view(Map<String, Object> x) {
    var v = new LinkedHashMap<>(x);
    if (Set.of("PENDING", "CHECKING").contains(x.get("status"))
        && Instant.parse((String) x.get("expiresAt")).isBefore(Instant.now()))
      v.put("status", "EXPIRED");
    return v;
  }

  public Map<String, Object> create(Account a, Map<String, Object> b) {
    docs.mutationLock();
    booking.expireHolds();
    require("DEMO".equals(getMode()) || "SANDBOX".equals(getMode()) || "LIVE".equals(getMode()), "Chuyển khoản hiện chưa khả dụng; vui lòng thanh toán tại quầy");
    require(Boolean.TRUE.equals(config().get("bankConfigured")), "Chưa cấu hình tài khoản nhận chuyển khoản; vui lòng thanh toán tại quầy");
    require(!"LIVE".equals(getMode()) || !webhookKey.isBlank(), "Chưa cấu hình xác minh ngân hàng; vui lòng thanh toán tại quầy");
    String type = oneOf((String) b.get("type"), "ORDER", "TOURNAMENT"),
        target = text((String) b.get("targetId"), "Mã đơn");
    String purpose = "BALANCE".equals(b.get("purpose")) ? "BALANCE" : "DEPOSIT";
    BigDecimal due;
    Instant expires = Instant.now().plusSeconds(900);
    if (type.equals("ORDER")) {
      var o = orders.findForUpdate(UUID.fromString(target)).orElseThrow();
      own(a, o.getCustomerAccount() == null ? null : o.getCustomerAccount().getId());
      require(
          Set.of("HOLDING", "CONFIRMED", "CHECKED_IN", "COMPLETED").contains(o.getStatus()),
          "Đơn không nhận thanh toán mới");
      due =
          (purpose.equals("DEPOSIT") ? o.getDepositRequired() : o.getTotalAmount())
              .subtract(o.getPaidAmount());
      if (due.signum() <= 0) due = o.getTotalAmount().subtract(o.getPaidAmount());
      if (o.getStatus().equals("HOLDING")) expires = o.getHoldExpiresAt();
    } else if (type.equals("TOURNAMENT")) {
      var r = registrations.findForUpdate(UUID.fromString(target)).orElseThrow();
      own(a, r.getAccount() == null ? null : r.getAccount().getId());
      require(
          !"CANCELLED".equals(r.getStatus()) && !"CANCELLED".equals(r.getTournament().getStatus()),
          "Đăng ký đã hủy");
      due = r.getTournament().getRegistrationFee().subtract(payments.registrationPaid(r.getId()));
    } else { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại giao dịch không hợp lệ"); }
    require(due.signum() > 0, "Không còn khoản cần thanh toán");
    for (var old : mine(a))
      if (type.equals(old.get("type"))
          && target.equals(old.get("targetId"))
          && Set.of("PENDING", "CHECKING").contains(old.get("status"))
          && due.compareTo(PresentationService.decimal(old.get("remaining"))) == 0)
        return old;

    String id = UUID.randomUUID().toString(),
        reference = "CT" + id.replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    var intent =
        map(
            "id", id,
            "accountId", a.getId().toString(),
            "type", type,
            "targetId", target,
            "amount", due,
            "remaining", due,
            "received", 0,
            "reviewAmount", 0,
            "reference", reference,
            "status", "PENDING",
            "mode", getMode(),
            "createdAt", Instant.now().toString(),
            "expiresAt", expires.toString());
    intent.putAll(config());

    String qr = vietQr(reference, due);
    String qrImg =
        "https://img.vietqr.io/image/"
            + getBankBin()
            + "-"
            + getAccountNumber()
            + "-compact2.png?amount="
            + due.setScale(0, RoundingMode.HALF_UP).toPlainString()
            + "&addInfo="
            + reference
            + "&accountName="
            + java.net.URLEncoder.encode(getAccountName(), StandardCharsets.UTF_8);

    intent.put("qrPayload", qr);
    intent.put("qrImageUrl", qrImg);
    docs.put("transfer:" + id, intent);
    return intent;
  }

  public Map<String, Object> declared(Account a, String id) {
    docs.mutationLock();
    var x = docs.get("transfer:" + id);
    require(!x.isEmpty(), "Giao dịch không tồn tại");
    own(a, x.get("accountId"));
    if ("PENDING".equals(view(x).get("status"))) {
      x.put("status", "CHECKING");
      docs.put("transfer:" + id, x);
    }
    return view(x);
  }

  public Map<String, Object> confirm(Account a, String id) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
        "Khách hàng không được xác nhận tiền đã thu; chờ xác minh từ ngân hàng");
  }

  public Map<String, Object> adminConfirm(String id) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
        "Chuyển khoản được xác minh qua webhook; thu tiền mặt qua phiếu thu tại quầy");
  }

  public Map<String, Object> sandbox(Account a, String id, Map<String, Object> b) {

    if (!"DEMO".equals(getMode()) && !"SANDBOX".equals(getMode()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mô phỏng chỉ dành cho môi trường thử nghiệm");
    docs.mutationLock();
    var x = docs.get("transfer:" + id);
    require(!x.isEmpty(), "Giao dịch không tồn tại");
    if (!"ADMIN".equals(a.getRole()) && !"STAFF".equals(a.getRole())) {
      own(a, x.get("accountId"));
    }
    String scenario = oneOf((String) b.get("scenario"), "SUCCESS", "PARTIAL", "DUPLICATE", "LATE");
    String event = "sandbox:" + id + ":" + scenario;
    if (!docs.get("bank-event:" + event).isEmpty()) return docs.get("transfer:" + id);
    if (scenario.equals("LATE")) {
      require("ORDER".equals(x.get("type")), "Kịch bản hết hạn áp dụng đơn sân");
      var o = orders.findForUpdate(UUID.fromString((String) x.get("targetId"))).orElseThrow();
      require("HOLDING".equals(o.getStatus()), "Chỉ mô phỏng hết hạn đơn đang giữ");
      o.setHoldExpiresAt(Instant.now().minusSeconds(1));
      orders.flush();
      booking.expireHolds();
      x.put("expiresAt", Instant.now().minusSeconds(1).toString());
      docs.put("transfer:" + id, x);
    }
    var due = PresentationService.decimal(x.get("remaining"));
    require(due.signum() > 0, "Giao dịch đã thu đủ");
    if (scenario.equals("PARTIAL"))
      due = due.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN).max(BigDecimal.ONE);
    receive(event, (String) x.get("reference"), due, "SANDBOX");
    if (scenario.equals("DUPLICATE"))
      receive(event, (String) x.get("reference"), due, "SANDBOX");
    return view(docs.get("transfer:" + id));
  }

  public Map<String, Object> webhook(String authorization, Map<String, Object> b) {
    if (!"LIVE".equals(getMode()) || webhookKey.isBlank())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook thanh toán chưa được cấu hình");
    if (authorization == null || !MessageDigest.isEqual(
        ("Apikey " + webhookKey).getBytes(StandardCharsets.UTF_8),
        authorization.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook key");
    require(Boolean.TRUE.equals(config().get("bankConfigured")), "Chưa cấu hình tài khoản nhận tiền");
    require(getAccountNumber().equals(String.valueOf(b.get("accountNumber"))), "Sai tài khoản nhận tiền");
    if (!"in".equals(b.get("transferType"))) return map("success", true, "ignored", true);
    String providerId = String.valueOf(b.getOrDefault("id", ""));
    require(providerId.matches("[0-9]{1,30}"), "Mã giao dịch ngân hàng không hợp lệ");
    String event = "sepay:" + providerId;
    var matcher =
        java.util.regex.Pattern.compile("(?<![A-Z0-9])CT[A-F0-9]{16}(?![A-Z0-9])")
            .matcher(String.valueOf(b.get("content")).toUpperCase(Locale.ROOT));
    String ref = matcher.find() ? matcher.group() : "UNMATCHED";
    receive(event, ref, amount(b.get("transferAmount")), "LIVE");
    return map("success", true);
  }

  private void receive(String event, String reference, BigDecimal received, String source) {
    docs.mutationLock();
    var prior = docs.get("bank-event:" + event);
    if (!prior.isEmpty()) {
      require(
          reference.equals(prior.get("reference"))
              && received.compareTo(PresentationService.decimal(prior.get("amount"))) == 0,
          "Mã giao dịch lặp với nội dung khác");
      return;
    }
    var x =
        docs.list("transfer:").stream()
            .filter(i -> reference.equals(i.get("reference")))
            .findFirst()
            .orElse(null);
    var record =
        map(
            "id", event,
            "reference", reference,
            "amount", received,
            "receivedAt", Instant.now().toString(),
            "status", "REVIEW",
            "source", source);
    if (x == null) {
      docs.put("bank-event:" + event, record);
      return;
    }
    booking.expireHolds();
    boolean valid =
        Instant.parse((String) x.get("expiresAt")).isAfter(Instant.now())
            && Set.of("PENDING", "CHECKING").contains(x.get("status"));
    BigDecimal due = PresentationService.decimal(x.get("remaining"));
    String type = (String) x.get("type"), target = (String) x.get("targetId");
    if (type.equals("ORDER")) {
      var o = orders.findForUpdate(UUID.fromString(target)).orElseThrow();
      valid &= Set.of("HOLDING", "CONFIRMED", "CHECKED_IN", "COMPLETED").contains(o.getStatus());
      due = due.min(o.getTotalAmount().subtract(o.getPaidAmount()));
    }
    if (type.equals("TOURNAMENT")) {
      var r = registrations.findForUpdate(UUID.fromString(target)).orElseThrow();
      valid &=
          !"CANCELLED".equals(r.getStatus())
              && !"CANCELLED".equals(r.getTournament().getStatus());
      due =
          due.min(
              r.getTournament().getRegistrationFee().subtract(payments.registrationPaid(r.getId())));
    }

    valid &= received.compareTo(due) <= 0 && due.signum() > 0;
    if (valid) {
      payments.collect(
          new PaymentRequest(
              type.equals("ORDER") ? UUID.fromString(target) : null,
              type.equals("TOURNAMENT") ? UUID.fromString(target) : null,
              received,
              "BANK_TRANSFER",
              event));
      x.put("received", PresentationService.decimal(x.get("received")).add(received));
      x.put("remaining", PresentationService.decimal(x.get("remaining")).subtract(received));
      // Mốc thời gian hệ thống ghi nhận tiền vào — dùng làm căn cứ đối soát trên biên lai
      x.put("paidAt", Instant.now().toString());
      x.put("bankTransactionCode", event);
      x.put(
          "status",
          PresentationService.decimal(x.get("remaining")).signum() == 0
              ? "CONFIRMED"
              : "CHECKING");
      record.put("status", "APPLIED");
    } else {
      x.put("reviewAmount", PresentationService.decimal(x.get("reviewAmount")).add(received));
      x.put("status", "REVIEW");
      if (type.equals("ORDER")) {
        var o = orders.findForUpdate(UUID.fromString(target)).orElseThrow();
        var meta = docs.decode(o.getMetadata());
        meta.put("financialReview", "MANUAL_REVIEW");
        meta.put(
            "transferReviewAmount",
            PresentationService.decimal(meta.get("transferReviewAmount")).add(received));
        o.setMetadata(docs.encode(meta));
      }
    }
    record.put("intentId", x.get("id"));
    docs.put("bank-event:" + event, record);
    docs.put("transfer:" + x.get("id"), x);
    if (valid) sendReceipt(x, event, received);
  }

  /** Email + thông báo in-app biên lai thanh toán. Lỗi gửi không được làm hỏng giao dịch đã ghi nhận. */
  private void sendReceipt(Map<String, Object> x, String event, BigDecimal received) {
    if (notifications == null || x.get("accountId") == null) return;
    try {
      UUID accountId = UUID.fromString(String.valueOf(x.get("accountId")));
      var account = accounts.findById(accountId).orElse(null);
      if (account == null) return;
      var zone = ZoneId.of("Asia/Ho_Chi_Minh");
      String paidAt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
          .format(Instant.parse(String.valueOf(x.get("paidAt"))).atZone(zone));
      UUID orderId = null;
      String target = String.valueOf(x.get("targetId"));
      String code;
      String purpose;
      if ("ORDER".equals(x.get("type"))) {
        orderId = UUID.fromString(target);
        code = "CT-" + target.substring(0, 8).toUpperCase(Locale.ROOT);
        purpose = "Thanh toán đơn đặt sân " + code;
      } else {
        code = "GD-" + target.substring(0, 8).toUpperCase(Locale.ROOT);
        purpose = "Lệ phí đăng ký giải đấu " + code;
      }
      String amount = String.format(Locale.GERMANY, "%,.0f₫", received);
      String remaining = String.format(Locale.GERMANY, "%,.0f₫", PresentationService.decimal(x.get("remaining")).max(BigDecimal.ZERO));
      String row = "<tr><td style=\"padding:6px 12px;color:#666\">%s</td><td style=\"padding:6px 12px;font-weight:600\">%s</td></tr>";
      String html =
          "<div style=\"font-family:Arial,sans-serif;max-width:560px\">"
              + "<h2 style=\"color:#173c32;margin:0 0 4px\">Biên lai thanh toán</h2>"
              + "<p style=\"color:#555;margin:0 0 14px\">" + getAccountName() + " · Courtly Tennis Club</p>"
              + "<table style=\"border-collapse:collapse;border:1px solid #d2dec8;width:100%\">"
              + String.format(row, "Số biên lai", x.get("reference"))
              + String.format(row, "Mã giao dịch ngân hàng", event)
              + String.format(row, "Thời gian ghi nhận", paidAt)
              + String.format(row, "Người thanh toán", account.getFullName())
              + String.format(row, "Diễn giải", purpose)
              + String.format(row, "Hình thức", "Chuyển khoản VietQR (Napas 247)")
              + String.format(row, "Số tiền đã thu", amount)
              + String.format(row, "Còn phải thanh toán", remaining)
              + "</table>"
              + "<p style=\"color:#777;font-size:12px\">Biên lai điện tử được lưu tại mục Đơn của tôi → Biên lai thanh toán. Vui lòng giữ email này để đối chiếu khi cần.</p>"
              + "</div>";
      String dedupe = "PAYMENT_RECEIPT:" + event;
      if (account.getEmail() != null && !account.getEmail().isBlank())
        notifications.enqueue(accountId, orderId, null, "PAYMENT_RECEIPT", "EMAIL", account.getEmail(),
            "[Courtly] Biên lai thanh toán " + x.get("reference") + " – " + amount, html, Instant.now(), dedupe + ":EMAIL");
      notifications.enqueue(accountId, orderId, null, "PAYMENT_RECEIPT", "IN_APP", null,
          "🧾 Đã nhận " + amount, purpose + " · " + paidAt + " · Mã GD " + x.get("reference"), Instant.now(), dedupe + ":IN_APP");
    } catch (Exception ignored) {
      // Biên lai vẫn xem lại được trong Đơn của tôi
    }
  }



  private static String tlv(String id, String s) {
    return id + String.format(Locale.ROOT, "%02d", s.length()) + s;
  }

  private String vietQr(String reference, BigDecimal amount) {
    String merchant =
        tlv("00", "A000000727")
            + tlv("01", tlv("00", getBankBin()) + tlv("01", getAccountNumber()))
            + tlv("02", "QRIBFTTA");
    String payload =
        tlv("00", "01")
            + tlv("01", "12")
            + tlv("38", merchant)
            + tlv("53", "704")
            + tlv("54", amount.setScale(0, RoundingMode.HALF_UP).toPlainString())
            + tlv("58", "VN")
            + tlv("62", tlv("08", reference))
            + "6304";
    int crc = 0xffff;
    for (byte v : payload.getBytes(StandardCharsets.UTF_8)) {
      crc ^= (v & 255) << 8;
      for (int i = 0; i < 8; i++) {
        crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
      }
      crc &= 0xffff;
    }
    return payload + String.format(Locale.ROOT, "%04X", crc);
  }
}
