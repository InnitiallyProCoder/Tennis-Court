import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../services/api';
import { withArchivedCourts } from '../utils/courts';
export function useClub(date, onAccessError, intervalMs = 30000) {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const serial = useRef(0);
  const refresh = useCallback(async () => {
    const request = ++serial.current;
    try {
      await api('/schedules/generate', 'POST', { date });
      const value = withArchivedCourts(await api(`/overview?date=${date}`));
      if (request === serial.current) { setData(value); setError(''); }
      return value;
    } catch (e) { if (request === serial.current) { setError(e.message); if ([401,403].includes(e.status)) {setData(null);onAccessError?.(e);} } throw e; }
    finally { if (request === serial.current) setLoading(false); }
  }, [date,onAccessError]);
  useEffect(() => {
    setLoading(true); refresh().catch(() => {});
    const timer = setInterval(() => { if (!document.hidden) refresh().catch(() => {}); }, intervalMs);
    // Quay lại tab / cửa sổ: tải ngay thay vì chờ tới chu kỳ kế tiếp
    const wake = () => { if (!document.hidden) refresh().catch(() => {}); };
    window.addEventListener('focus', wake); document.addEventListener('visibilitychange', wake);
    return () => { clearInterval(timer); serial.current++; window.removeEventListener('focus', wake); document.removeEventListener('visibilitychange', wake); };
  }, [refresh, intervalMs]);
  const mutate = useCallback(async (path, body, method = 'POST') => {
    const result = (await api(path, method, body)) || {};
    // The mutation is already committed. A refresh error must not invite duplicate writes.
    await refresh().catch(() => { result.refreshFailed=true;window.dispatchEvent(new CustomEvent('courtly-committed-refresh-error')); });
    // Thay đổi do chính người dùng tạo ra: không tính là "cập nhật mới" trên menu
    window.dispatchEvent(new CustomEvent('courtly-self-mutation'));
    return result;
  }, [refresh]);
  return { data, error, loading, refresh, mutate };
}
