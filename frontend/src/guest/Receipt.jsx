import React from 'react';
import {Printer} from 'lucide-react';
import {money,time,hourLabel} from './context';

// Biên lai điện tử (phiếu thu) cho một khoản tiền khách đã trả.
// Dùng chung cho hộp thoại "Giao dịch thành công" và mục Biên lai trong Đơn của tôi.

const pad=n=>String(n).padStart(2,'0');
export const fullStamp=v=>{
  if(!v)return '—';
  const d=new Date(v);
  if(Number.isNaN(d.getTime()))return '—';
  const p=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).formatToParts(d);
  const g=k=>p.find(x=>x.type===k)?.value||'';
  return `${g('day')}/${g('month')}/${g('year')} ${g('hour')}:${g('minute')}:${g('second')}`;
};
const orderCode=id=>id?`CT-${String(id).slice(0,8).toUpperCase()}`:'—';
const METHOD={BANK_TRANSFER:'Chuyển khoản VietQR (Napas 247)',CASH:'Tiền mặt tại quầy',CARD:'Thẻ ngân hàng'};

/** Gom dữ liệu biên lai từ khoản thu (payment) hoặc yêu cầu chuyển khoản (transfer) + đơn liên quan. */
export function buildReceipt({payment,transfer,order,data,user,club}){
  const courts=data?.courts||[];
  const slots=[...(order?.slots||[])].sort((a,b)=>String(a.start).localeCompare(String(b.start)));
  const court=slots.length?[...new Set(slots.map(s=>courts.find(c=>c.id===s.court)?.name).filter(Boolean))].join(', '):'';
  const start=time(slots[0]?.start)||(slots[0]?.hour!=null?hourLabel(slots[0].hour):'');
  const end=time(slots.at(-1)?.end)||(slots.at(-1)?.hour!=null?hourLabel(slots.at(-1).hour+1):'');
  const date=order?.date||slots[0]?.scheduleDate||'';
  const amount=Number(payment?.amount??transfer?.received??transfer?.amount??0);
  const total=Number(order?.total||0),paid=Number(order?.paid||0);
  const code=orderCode(order?.id||(transfer?.type==='ORDER'?transfer?.targetId:null)||payment?.orderId);
  const kind=!order||!(total>0)?'Thanh toán'
    :amount>=total?'Thanh toán toàn bộ'
    :paid>=total?'Thanh toán phần còn lại'
    :'Đặt cọc';
  const isTournament=transfer?.type==='TOURNAMENT'||(!order&&payment?.registrationId);
  return {
    receiptNo:transfer?.reference||(payment?.id?`PT-${String(payment.id).slice(0,8).toUpperCase()}`:'—'),
    bankCode:transfer?.bankTransactionCode||payment?.transactionCode||'',
    paidAt:transfer?.paidAt||payment?.createdAt||payment?.paidAt||null,
    payee:transfer?.accountName||club?.name||'CLB TENNIS COURTLY',
    payeeAddress:club?.address||'',
    payer:user?.fullName||user?.name||'',
    payerContact:[user?.phone,user?.email].filter(Boolean).join(' · '),
    orderCode:isTournament?'Lệ phí giải đấu':code,
    service:order?`${court||'Sân tennis'}${start?` · ${start}–${end}`:''}${date?` · ngày ${date}`:''}`:'',
    purpose:isTournament?'Lệ phí đăng ký giải đấu':`${kind} đơn đặt sân ${code}${court?` – ${court}`:''}${start?` (${start}–${end}${date?` ngày ${date}`:''})`:''}`,
    method:METHOD[payment?.method]||(transfer?'Chuyển khoản VietQR (Napas 247)':payment?.method||''),
    amount,
    total:order?total:null,
    paid:order?paid:null,
    remaining:order?Math.max(0,total-paid):null,
    status:transfer?.status||payment?.status||'PAID',
    collector:payment?.receivedBy?.name||''
  };
}

const rowsOf=(r,t)=>[
  [t('Số biên lai / Mã GD'),r.receiptNo],
  r.bankCode&&[t('Mã tham chiếu ngân hàng'),r.bankCode],
  [t('Thời gian ghi nhận'),fullStamp(r.paidAt)],
  [t('Đơn vị thu'),r.payee],
  [t('Người thanh toán'),[r.payer,r.payerContact].filter(Boolean).join(' – ')||'—'],
  [t('Mã đơn'),r.orderCode],
  r.service&&[t('Dịch vụ'),r.service],
  [t('Nội dung / Diễn giải'),r.purpose],
  [t('Hình thức'),r.method],
  r.collector&&[t('Thu ngân'),r.collector]
].filter(Boolean);

export function ReceiptCard({receipt:r,t=x=>x}){
  return (
    <div className="gx-receipt" style={{background:'#f0f4ec',border:'1px solid #d2dec8',borderRadius:6,padding:'14px 18px',textAlign:'left',fontSize:13}}>
      {rowsOf(r,t).map(([k,v])=>(
        <div key={k} style={{display:'flex',justifyContent:'space-between',gap:16,padding:'5px 0',borderBottom:'1px dashed #d2dec8'}}>
          <span style={{color:'#666',flexShrink:0}}>{k}:</span>
          <b style={{textAlign:'right',wordBreak:'break-word'}}>{v}</b>
        </div>
      ))}
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',paddingTop:8}}>
        <span style={{color:'#666'}}>{t('Số tiền đã thu')}:</span>
        <b style={{color:'#2e7d32',fontSize:16}}>{money(r.amount)}</b>
      </div>
      {r.total!=null&&(
        <div style={{display:'flex',justifyContent:'space-between',gap:12,flexWrap:'wrap',marginTop:6,fontSize:12,color:'#52655a'}}>
          <span>{t('Tổng đơn')}: <b>{money(r.total)}</b></span>
          <span>{t('Đã trả')}: <b>{money(r.paid)}</b></span>
          <span>{t('Còn lại')}: <b style={{color:r.remaining>0?'#b45309':'#15803d'}}>{money(r.remaining)}</b></span>
        </div>
      )}
    </div>
  );
}

const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

/** Mở cửa sổ in biên lai — khách chọn "Lưu dưới dạng PDF" để tải về. */
export function printReceipt(r,t=x=>x){
  const w=window.open('','_blank','width=720,height=860');
  if(!w)return false;
  const rows=rowsOf(r,t).map(([k,v])=>`<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join('');
  const totals=r.total!=null?`<tr><th>${esc(t('Tổng đơn'))}</th><td>${esc(money(r.total))}</td></tr><tr><th>${esc(t('Đã trả (lũy kế)'))}</th><td>${esc(money(r.paid))}</td></tr><tr><th>${esc(t('Còn phải thanh toán'))}</th><td>${esc(money(r.remaining))}</td></tr>`:'';
  w.document.write(`<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>${esc(t('Biên lai'))} ${esc(r.receiptNo)}</title>
<style>
body{font-family:Arial,Helvetica,sans-serif;color:#1f2d27;margin:32px}
.head{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:2px solid #173c32;padding-bottom:12px;margin-bottom:18px}
h1{font-size:22px;margin:0;color:#173c32;letter-spacing:.5px}
.muted{color:#667;font-size:12px;margin:4px 0 0}
.stamp{border:2px solid #2e7d32;color:#2e7d32;font-weight:700;padding:6px 12px;border-radius:6px;transform:rotate(-4deg);font-size:13px}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:8px 10px;border-bottom:1px solid #e3e8e0;text-align:left;vertical-align:top}
th{color:#556;font-weight:normal;width:38%}
td{font-weight:600}
.amount{margin:18px 0;padding:14px;background:#f0f4ec;border:1px solid #d2dec8;border-radius:6px;display:flex;justify-content:space-between;font-size:15px}
.amount b{font-size:20px;color:#2e7d32}
.foot{margin-top:28px;font-size:11px;color:#778}
@media print{body{margin:12mm}}
</style></head><body>
<div class="head"><div><h1>${esc(t('BIÊN LAI THANH TOÁN'))}</h1><p class="muted">${esc(r.payee)}${r.payeeAddress?' · '+esc(r.payeeAddress):''}</p><p class="muted">${esc(t('In lúc'))}: ${esc(fullStamp(new Date()))}</p></div><div class="stamp">${esc(t('ĐÃ THU TIỀN'))}</div></div>
<table>${rows}</table>
<div class="amount"><span>${esc(t('Số tiền đã thu'))}</span><b>${esc(money(r.amount))}</b></div>
<table>${totals}</table>
<p class="foot">${esc(t('Biên lai điện tử do hệ thống Courtly phát hành tự động, có giá trị đối chiếu giao dịch. Vui lòng xuất trình mã biên lai khi cần hỗ trợ.'))}</p>
<script>window.onload=function(){window.focus();window.print();}</script>
</body></html>`);
  w.document.close();
  return true;
}

export function PrintReceiptButton({receipt,t,notify,style,label}){
  return (
    <button type="button" className="ct-outline" style={{display:'inline-flex',alignItems:'center',justifyContent:'center',gap:6,...style}}
      onClick={()=>{if(!printReceipt(receipt,t))notify?.(t('Trình duyệt chặn cửa sổ in. Vui lòng cho phép pop-up rồi thử lại.'));}}>
      <Printer size={15}/> {label||t('In / Tải biên lai (PDF)')}
    </button>
  );
}
