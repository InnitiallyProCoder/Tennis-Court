package com.courtly.service;

import static com.courtly.util.Rules.*;

import com.courtly.entity.Account;
import com.courtly.entity.CustomerOrder;
import com.courtly.exception.BusinessException;
import com.courtly.repository.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class ContentService {
  private final DocumentService docs;
  private final AuthService auth;
  private final TournamentRepository tournaments;
  private final RequestService requests;
  private final CustomerOrderRepository orders;
  private final BookingRepository bookings;
  private final BookingSlotRepository bookingSlots;
  private final AccountRepository accounts;
  private final VoucherService vouchers;
  private final com.courtly.repository.VoucherRepository vouchersRepo;
  private final NotificationService notificationService;
  private final AuditService auditService;
  @org.springframework.beans.factory.annotation.Autowired
  @org.springframework.context.annotation.Lazy
  private PaymentService paymentService;
  @org.springframework.beans.factory.annotation.Autowired
  @org.springframework.context.annotation.Lazy
  private PaymentRepository paymentsRepo;

  private String string(Map<String, Object> b, String k, int max, boolean required) {
    Object v = b.getOrDefault(k, "");
    require(v instanceof String, k + " không hợp lệ");
    String s = ((String) v).trim();
    require(s.length() <= max && (!required || !s.isEmpty()), k + " không hợp lệ");
    return s;
  }

  public Map<String, Object> club() {
    var saved = docs.get("club");
    if (!saved.isEmpty()) return saved;
    try {
      return docs.decode(
          new String(
              new ClassPathResource("default-club.json").getInputStream().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> get(Account a) {
    boolean admin = a != null && "ADMIN".equals(a.getRole());
    var articles = new ArrayList<>(docs.list("article:"));
    if (!admin)
      articles.removeIf(
          item ->
              !"PUBLISHED".equals(item.get("status"))
                  || (item.get("publishedAt") != null
                      && Instant.parse((String) item.get("publishedAt")).isAfter(Instant.now()))
                  || (item.get("expiresAt") != null
                      && Instant.parse((String) item.get("expiresAt")).isBefore(Instant.now())));
    for (var item : articles)
      if (item.get("tournamentId") != null)
        tournaments
            .findById(UUID.fromString((String) item.get("tournamentId")))
            .ifPresent(
                t ->
                    item.put(
                        "tournament",
                        map(
                            "id",
                            t.getId(),
                            "name",
                            t.getName(),
                            "status",
                            t.getStatus(),
                            "format",
                            t.getFormat(),
                            "entryFee",
                            t.getRegistrationFee(),
                            "startDate",
                            t.getStartDate(),
                            "endDate",
                            t.getEndDate(),
                            "max",
                            t.getMaxParticipants(),
                            "location",
                            t.getLocation())));
    articles.sort(
        Comparator.comparing(
            aItem -> String.valueOf(aItem.getOrDefault("publishedAt", "")), Comparator.reverseOrder()));
    var feedback = new ArrayList<Map<String, Object>>();
    for (var f : docs.list("feedback:"))
      if (admin || (a != null && "STAFF".equals(a.getRole()))) feedback.add(f);
      else if ("PUBLISHED".equals(f.get("status"))
          && Boolean.TRUE.equals(f.get("publishConsent"))) {
        var item = new LinkedHashMap<>(f);
        item.remove("accountId");
        item.remove("requestId");
        item.remove("publishConsent");
        feedback.add(item);
      }
    Object avg =
        feedback.isEmpty()
            ? null
            : Math.round(
                    feedback.stream()
                            .mapToInt(f -> ((Number) f.get("rating")).intValue())
                            .average()
                            .orElse(0)
                        * 10)
                / 10.0;
    return map(
        "club",
        club(),
        "articles",
        articles,
        "feedback",
        feedback,
        "sample",
        false,
        "feedbackStats",
        map("count", feedback.size(), "average", avg),
        "tournaments",
        admin
            ? tournaments.findAll().stream()
                .map(t -> map("id", t.getId(), "name", t.getName(), "status", t.getStatus()))
                .toList()
            : List.of());
  }

  public List<Map<String, Object>> mine(Account a) {
    return docs.list("feedback:").stream()
        .filter(f -> a.getId().toString().equals(f.get("accountId")))
        .toList();
  }

  public List<Map<String, Object>> listEligibleOrders(Account a) {
    if (a == null) return List.of();
    Set<String> reviewedOrderIds = docs.list("feedback:").stream()
        .map(f -> (String) f.get("orderId"))
        .filter(Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());

    List<CustomerOrder> customerOrders = orders.findByCustomerAccountIdOrderByCreatedAtDesc(a.getId());
    Instant cutOff = Instant.now().minus(java.time.Duration.ofDays(14));

    List<Map<String, Object>> result = new ArrayList<>();
    for (CustomerOrder o : customerOrders) {
      if (!Set.of("COMPLETED", "CHECKED_IN").contains(o.getStatus())) {
        continue;
      }
      if (o.getCreatedAt() != null && o.getCreatedAt().isBefore(cutOff)) {
        continue;
      }
      if (reviewedOrderIds.contains(o.getId().toString())) {
        continue;
      }

      String orderCode = "CT-" + o.getId().toString().substring(0, 8).toUpperCase();
      String visitDate = null;
      Set<String> courtNames = new LinkedHashSet<>();
      for (var b : bookings.findByOrderId(o.getId())) {
        for (var bs : bookingSlots.findByBookingId(b.getId())) {
          if (bs.getSchedule() != null) {
            if (visitDate == null && bs.getSchedule().getScheduleDate() != null) {
              visitDate = bs.getSchedule().getScheduleDate().toString();
            }
            if (bs.getSchedule().getCourt() != null) {
              courtNames.add(bs.getSchedule().getCourt().getName());
            }
          }
        }
      }

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", o.getId());
      item.put("orderCode", orderCode);
      item.put("date", visitDate != null ? visitDate : (o.getCreatedAt() != null ? o.getCreatedAt().toString().substring(0, 10) : ""));
      item.put("courtNames", String.join(", ", courtNames));
      item.put("total", o.getTotalAmount());
      result.add(item);
    }
    return result;
  }

  public Map<String, Object> save(Account a, String method, String path, Map<String, Object> b) {
    return requests.execute(
        a.getId(),
        (String) b.get("requestId"),
        method + path,
        b,
        () -> {
          if (path.equals("/api/feedback")) {
            String msg = string(b, "message", 2000, true);
            require(msg.length() >= 10, "Nội dung cần ít nhất 10 ký tự");
            int rating = b.get("rating") instanceof Number n ? n.intValue() : 0;
            require(b.get("rating") instanceof Number n && n.doubleValue() == rating && rating >= 1 && rating <= 5, "Đánh giá từ 1 đến 5 sao");
            oneOf((String) b.get("topic"), "COURT", "SERVICE", "FACILITY", "WEBSITE", "TOURNAMENT", "OTHER");
            require(b.get("publishConsent") instanceof Boolean, "Cần chọn quyền công khai");
            var recent =
                mine(a).stream()
                    .filter(
                        f ->
                            Instant.parse((String) f.get("createdAt"))
                                .isAfter(Instant.now().minusSeconds(86400)))
                    .toList();
            require(
                recent.size() < 5
                    && recent.stream()
                        .noneMatch(
                            f ->
                                Instant.parse((String) f.get("createdAt"))
                                    .isAfter(Instant.now().minusSeconds(60))),
                "Đợi một phút; tối đa 5 phản hồi mỗi ngày");
            String id = UUID.randomUUID().toString();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", id);
            doc.put("accountId", a.getId().toString());
            doc.put("displayName", a.getFullName());
            doc.put("rating", rating);
            doc.put("topic", b.get("topic"));
            doc.put("message", msg);
            doc.put("publishConsent", b.get("publishConsent"));
            doc.put("status", "PENDING");
            doc.put("createdAt", Instant.now().toString());
            doc.put("reply", "");

            if (b.get("orderId") != null && !String.valueOf(b.get("orderId")).isBlank()) {
              try {
                UUID orderId = UUID.fromString(String.valueOf(b.get("orderId")));
                CustomerOrder o = orders.findById(orderId).orElse(null);
                if (o != null) {
                  doc.put("orderId", orderId.toString());
                  String visitDate = null;
                  Set<String> courtNames = new LinkedHashSet<>();
                  for (var bk : bookings.findByOrderId(o.getId())) {
                    for (var bs : bookingSlots.findByBookingId(bk.getId())) {
                      if (bs.getSchedule() != null) {
                        if (visitDate == null && bs.getSchedule().getScheduleDate() != null) {
                          visitDate = bs.getSchedule().getScheduleDate().toString();
                        }
                        if (bs.getSchedule().getCourt() != null) {
                          courtNames.add(bs.getSchedule().getCourt().getName());
                        }
                      }
                    }
                  }
                  doc.put("visitDate", visitDate);
                  doc.put("courtNames", String.join(", ", courtNames));
                }
              } catch (Exception ignored) {}
            }

            if (rating <= 3) {
              doc.put("lowRating", true);
              doc.put("handlingStatus", "NEW");
              doc.put("dueAt", Instant.now().plus(java.time.Duration.ofHours(24)).toString());
            } else {
              doc.put("lowRating", false);
            }

            docs.put("feedback:" + id, doc);
            return map("id", id);
          }

          if (path.equals("/api/content/club")) {
            auth.admin(a);
            var c = club();
            for (String key : new ArrayList<>(c.keySet())) {
              String v =
                  string(
                      b,
                      key,
                      key.equals("story") ? 5000 : 2000,
                      Set.of("name", "tagline", "intro", "story", "mission").contains(key));
              if (Set.of("mapUrl", "socialUrl").contains(key) && !v.isEmpty()) {
                var u = java.net.URI.create(v);
                require(
                    "https".equals(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null,
                    "URL cần HTTPS hợp lệ");
              }
              c.put(key, v);
            }
            docs.put("club", c);
            return map("ok", true);
          }

          String[] parts = path.split("/");
          if (parts[3].equals("articles")) {
            auth.admin(a);
            String id = parts.length > 4 ? parts[4] : UUID.randomUUID().toString();
            var old = docs.get("article:" + id);
            require(parts.length <= 4 || !old.isEmpty(), "Bài viết không tồn tại");
            oneOf((String) b.get("status"), "DRAFT", "PUBLISHED");
            oneOf((String) b.get("category"), "NEWS", "NOTICE", "TOURNAMENT", "PROMO");
            String tournament = String.valueOf(b.getOrDefault("tournamentId", ""));
            if (!tournament.isBlank() && !tournament.equals("null")) {
              var t = tournaments.findById(UUID.fromString(tournament)).orElseThrow();
              require(
                  !"PUBLISHED".equals(b.get("status")) || !"DRAFT".equals(t.getStatus()),
                  "Công bố giải trước khi xuất bản tin");
            }
            var row =
                map(
                    "id",
                    id,
                    "title",
                    string(b, "title", 160, true),
                    "excerpt",
                    string(b, "excerpt", 400, true),
                    "body",
                    string(b, "body", 12000, true),
                    "category",
                    b.get("category"),
                    "status",
                    b.get("status"),
                    "tournamentId",
                    tournament.isBlank() ? null : tournament,
                    "source",
                    "EDITORIAL",
                    "createdAt",
                    old.getOrDefault("createdAt", Instant.now().toString()),
                    "updatedAt",
                    Instant.now().toString(),
                    "publishedAt",
                    "PUBLISHED".equals(b.get("status"))
                        ? (old.get("publishedAt") == null
                            ? Instant.now().toString()
                            : old.get("publishedAt"))
                        : null);
            for (String keep : List.of("campaignId", "expiresAt")) {
              if (old.get(keep) != null) row.put(keep, old.get(keep));
            }
            if ("CAMPAIGN".equals(old.get("source"))) row.put("source", "CAMPAIGN");
            docs.put("article:" + id, row);
            return map("id", id);
          }

          if (!Set.of("ADMIN", "STAFF").contains(a.getRole())) {
            throw new BusinessException("Chỉ Manager và Nhân viên mới có quyền cập nhật feedback");
          }
          var f = docs.get("feedback:" + parts[4]);
          require(!f.isEmpty(), "Phản hồi không tồn tại");
          oneOf((String) b.get("status"), "PENDING", "PUBLISHED", "PRIVATE");
          require(
              !"PUBLISHED".equals(b.get("status")) || Boolean.TRUE.equals(f.get("publishConsent")),
              "Khách chưa đồng ý công khai");
          if ("PUBLISHED".equals(b.get("status"))) {
            auth.admin(a);
          }
          f.put("status", b.get("status"));
          f.put("reply", string(b, "reply", 2000, false));
          f.put("repliedAt", Instant.now().toString());
          docs.put("feedback:" + parts[4], f);
          return map("id", parts[4]);
        });
  }

  public Map<String, Object> assignFeedback(String id, Account actor) {
    if (!Set.of("ADMIN", "STAFF").contains(actor.getRole())) {
      throw new BusinessException("Chỉ Manager và Nhân viên mới có quyền nhận xử lý feedback");
    }
    var f = docs.get("feedback:" + id);
    require(!f.isEmpty(), "Phản hồi không tồn tại");
    f.put("assignedTo", actor.getFullName());
    f.put("handlingStatus", "IN_PROGRESS");
    docs.put("feedback:" + id, f);
    auditService.log("ASSIGN_FEEDBACK", "Nhận xử lý feedback " + id + " bởi " + actor.getFullName());
    return f;
  }

  public Map<String, Object> resolveFeedback(String id, Map<String, Object> body, Account actor) {
    if (!Set.of("ADMIN", "STAFF").contains(actor.getRole())) {
      throw new BusinessException("Chỉ Manager và Nhân viên mới có quyền xử lý feedback");
    }
    var f = docs.get("feedback:" + id);
    require(!f.isEmpty(), "Phản hồi không tồn tại");
    String handlingStatus = (String) body.getOrDefault("status", "RESOLVED");
    f.put("handlingStatus", handlingStatus);
    f.put("resolution", body.get("resolution"));
    f.put("resolvedBy", actor.getFullName());
    f.put("resolvedAt", Instant.now().toString());

    if (body.get("reply") != null && !String.valueOf(body.get("reply")).isBlank()) {
      String replyText = String.valueOf(body.get("reply")).trim();
      f.put("reply", replyText);
      f.put("repliedAt", Instant.now().toString());

      if (f.get("accountId") != null) {
        try {
          UUID accId = UUID.fromString(String.valueOf(f.get("accountId")));
          accounts.findById(accId).ifPresent(target -> {
            String dedupe = "FEEDBACK_REPLY:" + id + ":" + System.currentTimeMillis();
            if (target.getEmail() != null) {
              notificationService.enqueue(accId, null, null, "FEEDBACK_REPLY", "EMAIL", target.getEmail(),
                  "[Courtly] Phản hồi đánh giá của bạn", "<p>" + replyText + "</p>", Instant.now(), dedupe + ":EMAIL");
            }
            notificationService.enqueue(accId, null, null, "FEEDBACK_REPLY", "IN_APP", null,
                "💬 Phản hồi đánh giá", replyText, Instant.now(), dedupe + ":IN_APP");
          });
        } catch (Exception ignored) {}
      }
    }

    docs.put("feedback:" + id, f);
    auditService.log("RESOLVE_FEEDBACK", "Xử lý feedback " + id + " trạng thái " + handlingStatus + " bởi " + actor.getFullName());
    return f;
  }

  public record CompensationTemplate(
      String code,
      String title,
      String description,
      String type,
      Number value,
      Number minOrder,
      Number maxDiscount,
      int days,
      Set<String> allowedRoles
  ) {}

  public static final List<CompensationTemplate> COMPENSATION_TEMPLATES = List.of(
      new CompensationTemplate(
          "APOLOGY_50K",
          "Xin lỗi nhẹ (50.000₫)",
          "Giảm 50.000₫ cho đơn từ 100.000₫, hiệu lực 30 ngày",
          "FIXED",
          50000,
          100000,
          null,
          30,
          Set.of("ADMIN", "STAFF")
      ),
      new CompensationTemplate(
          "SERVICE_20",
          "Bù dịch vụ (20% max 100k)",
          "Giảm 20% tối đa 100.000₫ cho đơn từ 100.000₫, hiệu lực 30 ngày",
          "PERCENT",
          20,
          100000,
          100000,
          30,
          Set.of("ADMIN", "STAFF")
      ),
      new CompensationTemplate(
          "FREE_COURT_1H",
          "1 giờ sân miễn phí",
          "Giảm 100% (tối đa 350.000₫), hiệu lực 60 ngày. (Chỉ ADMIN)",
          "PERCENT",
          100,
          0,
          350000,
          60,
          Set.of("ADMIN")
      )
  );

  public List<Map<String, Object>> getCompensationTemplates(Account actor) {
    if (actor == null) return List.of();
    String role = actor.getRole();
    return COMPENSATION_TEMPLATES.stream()
        .filter(t -> t.allowedRoles().contains(role))
        .map(t -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("code", t.code());
          m.put("id", t.code());
          m.put("title", t.title());
          m.put("description", t.description());
          m.put("desc", t.description());
          m.put("type", t.type());
          m.put("value", t.value());
          m.put("minOrder", t.minOrder());
          m.put("maxDiscount", t.maxDiscount());
          m.put("days", t.days());
          return m;
        })
        .toList();
  }

  public Map<String, Object> compensateFeedback(String id, Map<String, Object> body, Account actor) {
    if (!Set.of("ADMIN", "STAFF").contains(actor.getRole())) {
      throw new BusinessException("Chỉ Manager và Nhân viên mới có quyền tặng voucher bồi thường");
    }

    String templateCode = body != null && body.get("templateCode") != null ? String.valueOf(body.get("templateCode")).trim() : "";
    if (templateCode.isBlank()) {
      throw new BusinessException("Mã mẫu voucher bù không hợp lệ");
    }

    Map<String, Object> template = new HashMap<>();

    if ("CUSTOM".equalsIgnoreCase(templateCode)) {
      if (!"ADMIN".equals(actor.getRole())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mẫu tuỳ chỉnh chỉ dành cho Manager");
      }
      String reason = body.get("reason") != null ? String.valueOf(body.get("reason")).trim() : "";
      if (reason.length() < 10) {
        throw new BusinessException("Lý do bù tuỳ chỉnh phải có ít nhất 10 ký tự");
      }
      String customType = "PERCENT".equalsIgnoreCase(String.valueOf(body.get("type"))) ? "PERCENT" : "FIXED";
      Object customValObj = body.get("value");
      require(customValObj != null, "Giá trị giảm không được để trống");
      Number customVal = (Number) customValObj;
      require(customVal.doubleValue() > 0, "Giá trị giảm phải lớn hơn 0");
      Number minOrder = body.get("minOrder") instanceof Number ? (Number) body.get("minOrder") : 0;
      Number maxDiscount = body.get("maxDiscount") instanceof Number ? (Number) body.get("maxDiscount") : null;
      int days = body.get("days") instanceof Number ? ((Number) body.get("days")).intValue() : 30;

      template.put("type", customType);
      template.put("value", customVal);
      template.put("minOrder", minOrder);
      if (maxDiscount != null) template.put("maxDiscount", maxDiscount);
      template.put("startsAt", Instant.now().toString());
      template.put("expiresAt", Instant.now().plus(java.time.Duration.ofDays(days)).toString());
      template.put("description", "Quà xin lỗi dịch vụ: " + reason);

      auditService.log("CUSTOM_COMPENSATION", "Admin " + actor.getFullName() + " tặng voucher tuỳ chỉnh (" + customType + " " + customVal + "): " + reason);
    } else {
      CompensationTemplate tpl = COMPENSATION_TEMPLATES.stream()
          .filter(t -> t.code().equalsIgnoreCase(templateCode))
          .findFirst()
          .orElse(null);
      if (tpl == null) {
        throw new BusinessException("Mã mẫu voucher bù không hợp lệ");
      }
      if (!tpl.allowedRoles().contains(actor.getRole())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mẫu này cần Manager");
      }

      template.put("type", tpl.type());
      template.put("value", tpl.value());
      template.put("minOrder", tpl.minOrder());
      if (tpl.maxDiscount() != null) {
        template.put("maxDiscount", tpl.maxDiscount());
      }
      template.put("startsAt", Instant.now().toString());
      template.put("expiresAt", Instant.now().plus(java.time.Duration.ofDays(tpl.days())).toString());
      template.put("description", "Quà xin lỗi dịch vụ: " + tpl.title());
    }

    var docRow = docs.lock("feedback:" + id);
    require(docRow != null, "Phản hồi không tồn tại");
    var f = docs.read(docRow);

    if (f.get("compensationVoucherCode") != null) {
      String code = String.valueOf(f.get("compensationVoucherCode"));
      var existingVoucher = vouchersRepo.findByCodeIgnoreCase(code).orElse(null);
      if (existingVoucher != null && !"REVOKED".equals(existingVoucher.getStatus())) {
        throw new BusinessException("Phản hồi này đã được tặng voucher " + code + ". Để thay đổi mức bù, vui lòng thu hồi voucher cũ trước.");
      }
    }

    UUID targetAccountId = UUID.fromString(String.valueOf(f.get("accountId")));
    Account target = accounts.findById(targetAccountId).orElseThrow(() -> new BusinessException("Không tìm thấy tài khoản khách hàng"));

    UUID orderId = null;
    if (f.get("orderId") != null && !String.valueOf(f.get("orderId")).isBlank()) {
      try {
        orderId = UUID.fromString(String.valueOf(f.get("orderId")));
      } catch (Exception ignored) {}
    }

    var voucher = vouchers.createCompensationVoucher(targetAccountId, orderId, id, template, actor);

    f.put("compensationVoucherId", voucher.getId().toString());
    f.put("compensationVoucherCode", voucher.getCode());
    f.put("compensatedAt", Instant.now().toString());
    f.put("compensatedBy", actor.getFullName());
    docs.save(docRow, f);

    String courtNames = f.get("courtNames") != null ? String.valueOf(f.get("courtNames")) : "sân";
    String visitDate = f.get("visitDate") != null ? String.valueOf(f.get("visitDate")) : "";
    String sms = "Courtly xin loi vi trai nghiem chua tot tai " + courtNames + (visitDate.isEmpty() ? "" : " ngay " + visitDate) + ". Tang ban ma " + voucher.getCode() + " " + voucher.getDescription() + ", han den " + (voucher.getExpiresAt() != null ? voucher.getExpiresAt().toString().substring(0, 10) : "") + ".";
    String dedupe = "COMPENSATION_VOUCHER:" + id + ":" + voucher.getId();

    if (target.getEmail() != null) {
      notificationService.enqueue(targetAccountId, orderId, null, "COMPENSATION_VOUCHER", "EMAIL", target.getEmail(),
          "[Courtly] Quà xin lỗi & Voucher bồi thường trải nghiệm dịch vụ", "<p>" + sms + "</p>", Instant.now(), dedupe + ":EMAIL");
    }
    notificationService.enqueue(targetAccountId, orderId, null, "COMPENSATION_VOUCHER", "IN_APP", null,
        "🎁 Voucher bồi thường trải nghiệm", sms, Instant.now(), dedupe + ":IN_APP");

    auditService.log("COMPENSATE_FEEDBACK", "Tặng voucher " + voucher.getCode() + " cho feedback " + id + " bởi " + actor.getFullName());

    return map("id", voucher.getId(), "code", voucher.getCode(), "status", voucher.getStatus());
  }

  public Map<String, Object> revokeCompensation(String id, Map<String, Object> body, Account actor) {
    if (!"ADMIN".equals(actor.getRole())) {
      throw new BusinessException("Chỉ Manager mới có quyền thu hồi voucher bồi thường");
    }
    String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
    require(reason.length() >= 5, "Lý do thu hồi phải có ít nhất 5 ký tự");

    var docRow = docs.lock("feedback:" + id);
    require(docRow != null, "Phản hồi không tồn tại");
    var f = docs.read(docRow);

    String code = (String) f.get("compensationVoucherCode");
    require(code != null && !code.isBlank(), "Phản hồi này chưa được tặng voucher hoặc voucher đã bị thu hồi trước đó");

    com.courtly.entity.Voucher v = vouchersRepo.findByCodeIgnoreCase(code)
        .orElseThrow(() -> new BusinessException("Không tìm thấy voucher bồi thường " + code));
    if (v.getUsedCount() > 0) {
      throw new BusinessException("Voucher " + code + " đã được khách hàng sử dụng, không thể thu hồi");
    }

    v.setStatus("REVOKED");
    v.setEnabled(false);
    vouchersRepo.save(v);

    f.put("revokedCompensationVoucherCode", code);
    f.remove("compensationVoucherId");
    f.remove("compensationVoucherCode");
    f.remove("compensatedAt");
    f.put("compensationRevokedAt", Instant.now().toString());
    f.put("compensationRevokeReason", reason);
    docs.save(docRow, f);

    auditService.log("REVOKE_COMPENSATION", "Thu hồi voucher " + code + " cho phản hồi " + id + " lý do: " + reason);

    return map("success", true, "revokedCode", code, "message", "Đã thu hồi voucher bồi thường " + code);
  }

  private UUID feedbackOrderId(Map<String, Object> f) {
    Object raw = f.get("orderId");
    require(raw != null && !String.valueOf(raw).isBlank(), "Phản hồi không gắn với đơn nào nên không thể hoàn tiền");
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException e) {
      throw new BusinessException("Mã đơn của phản hồi không hợp lệ");
    }
  }

  private java.math.BigDecimal feedbackRefunded(Map<String, Object> f) {
    return PresentationService.decimal(f.get("refundedTotal")).max(java.math.BigDecimal.ZERO);
  }

  /** Số tiền còn có thể hoàn cho đơn gắn với phản hồi (tổng đã thu trừ các khoản đã hoàn). */
  @Transactional(readOnly = true)
  public Map<String, Object> feedbackRefundInfo(String id, Account actor) {
    if (!Set.of("ADMIN", "STAFF").contains(actor.getRole()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền");
    var f = docs.get("feedback:" + id);
    require(!f.isEmpty(), "Phản hồi không tồn tại");
    UUID orderId = feedbackOrderId(f);
    var paid = paymentsRepo.findByOrderId(orderId).stream()
        .map(com.courtly.entity.Payment::getAmount)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    var refundable = paymentsRepo.findByOrderId(orderId).stream()
        .map(paymentService::net)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
        .max(java.math.BigDecimal.ZERO);
    return map(
        "orderId", orderId,
        "paid", paid,
        "refundable", refundable,
        "refundedTotal", feedbackRefunded(f),
        "refunds", f.getOrDefault("refunds", List.of()),
        "canRefund", "ADMIN".equals(actor.getRole()));
  }

  /**
   * Hoàn tiền thiện chí cho khách từ ca xử lý feedback. Đây là khoản hoàn ngoài chính sách hủy nên
   * chỉ Manager được thực hiện; tiền được trừ lần lượt trên các giao dịch đã thu của đơn.
   */
  public Map<String, Object> refundFeedback(String id, Map<String, Object> body, Account actor) {
    if (!"ADMIN".equals(actor.getRole()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Hoàn tiền thiện chí từ feedback cần Manager thực hiện");
    require(body != null, "Thiếu thông tin hoàn tiền");
    java.math.BigDecimal amount = PresentationService.decimal(body.get("amount"));
    require(amount.signum() > 0, "Số tiền hoàn phải lớn hơn 0");
    require(amount.stripTrailingZeros().scale() <= 0, "Số tiền hoàn phải là số nguyên (₫)");
    String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason")).trim();
    require(reason.length() >= 10, "Lý do hoàn tiền cần ít nhất 10 ký tự");
    require(reason.length() <= 200, "Lý do hoàn tiền tối đa 200 ký tự");

    var docRow = docs.lock("feedback:" + id);
    require(docRow != null, "Phản hồi không tồn tại");
    var f = docs.read(docRow);
    UUID orderId = feedbackOrderId(f);
    CustomerOrder order = orders.findById(orderId).orElseThrow(() -> new BusinessException("Không tìm thấy đơn liên quan"));

    var candidates = new ArrayList<com.courtly.entity.Payment>(paymentsRepo.findByOrderId(orderId));
    candidates.sort(Comparator.comparing(com.courtly.entity.Payment::getPaidAt,
        Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
    java.math.BigDecimal refundable = candidates.stream()
        .map(paymentService::net)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    require(refundable.signum() > 0, "Đơn này chưa có khoản đã thu nào để hoàn");
    require(amount.compareTo(refundable) <= 0,
        "Chỉ có thể hoàn tối đa " + String.format(Locale.GERMANY, "%,.0f₫", refundable) + " (số tiền khách đã trả còn lại)");

    String refundReason = "Hoàn thiện chí (feedback): " + reason;
    if (refundReason.length() > 255) refundReason = refundReason.substring(0, 255);
    java.math.BigDecimal remaining = amount;
    var refundIds = new ArrayList<String>();
    for (var p : candidates) {
      if (remaining.signum() <= 0) break;
      java.math.BigDecimal net = paymentService.net(p);
      if (net.signum() <= 0) continue;
      java.math.BigDecimal part = net.min(remaining);
      refundIds.add(paymentService.refund(p.getId(), new com.courtly.dto.request.RefundRequest(part, refundReason), actor).toString());
      remaining = remaining.subtract(part);
    }

    @SuppressWarnings("unchecked")
    var history = new ArrayList<Object>(f.get("refunds") instanceof List<?> l ? (List<Object>) l : List.of());
    history.add(map(
        "amount", amount,
        "reason", reason,
        "by", actor.getFullName(),
        "at", Instant.now().toString(),
        "refundIds", refundIds));
    f.put("refunds", history);
    f.put("refundedTotal", feedbackRefunded(f).add(amount));
    docs.save(docRow, f);

    Account customer = order.getCustomerAccount();
    UUID accountId = customer != null ? customer.getId() : null;
    String email = customer != null ? customer.getEmail() : null;
    String phone = customer != null ? customer.getPhone() : null;
    try {
      notificationService.notifyRefundDone(orderId, accountId, phone, email, amount.longValue());
    } catch (Exception ignored) {}

    auditService.log("REFUND_FEEDBACK", "Hoàn " + amount.toPlainString() + "₫ cho đơn " + orderId
        + " từ feedback " + id + " bởi Manager " + actor.getFullName() + ": " + reason);

    return map("refunded", amount, "refundedTotal", f.get("refundedTotal"),
        "refundable", refundable.subtract(amount), "refunds", history);
  }
}
