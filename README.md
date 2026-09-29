# Brebde 🐆

پنل پروکسی تک‌فایلی روی Cloudflare Workers — سبکِ BPB ولی بهینه‌شده برای پلن رایگان (CPU و subrequest کمتر، محدودیت دیرتر).
VLESS + Trojan روی WebSocket، کاربران KV با کوتا/انقضا/محدودیت دستگاه، اسکنر آی‌پی تمیز، DoH، پنل فارسی/انگلیسی.

## 🚀 دکمه‌ی دیپلوی یک‌کلیکی

روی دکمه بزن، وارد اکانت Cloudflare شو، تمام — KV خودکار ساخته و متصل می‌شه و ورکر دیپلوی می‌شه:

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/imsamiyar/brebde)

بعد از دیپلوی، آدرس `https://brebde.<your-subdomain>.workers.dev` پنلته. اولین کار: توی پنل، از بخش Tools یه **پسورد پنل** بذار.

## چطور ازش استفاده کنم

1. دکمه‌ی بالا → Clone + Deploy (چند ثانیه)
2. آدرس ورکر رو باز کن → پنل میاد
3. پسورد بذار (Tools → settings)
4. لینک اشتراک `/sub/<uuid>` رو توی کلاینت (v2rayNG / Hiddify / Brebde / Clash Meta) وارد کن
5. تب «کانفیگ‌ها» → اسکن → بهترین آی‌پی‌های تمیز خودش ذخیره می‌شن

## چرا بهینه‌تر از بقیه‌ست؟

- شل پنل ۶۰ ثانیه edge-cache می‌شه (بازکردن پنل CPU صفر)
- اسکن سنگین فقط با دسترسی مالک اجرا می‌شه — رفرش خودکار ساب کلاینت‌ها پروب سمت سرور نمی‌زنه
- پروکسی sticky: آخرین IP موفق اول امتحان می‌شه (subrequest هدر نمی‌ره)
- کرون ۶ ساعته، shots اسکن محدود — داخل سقف ۵۰ subrequest/ریکوئست پلن رایگان
- KV read-cache داخلی (TTL 3s) برای کاهش خواندن KV

## API اشتراک

| مسیر | خروجی |
|---|---|
| `/sub/<uuid>` | لینک‌های خام |
| `/sub64/<uuid>` | base64 (v2rayNG) |
| `/sub/<uuid>/clash` | Clash/Mihomo YAML |
| `/sub/<uuid>/singbox` | sing-box JSON |

## متغیرهای محیطی (اختیاری)

`UUID` · `SNI` · `SNI_LIST` · `PROXY_IPS` · `PANEL_PASSWORD` · `PANEL_TITLE` · `USER_TOTAL` — همه در `wrangler.jsonc` یا داشبورد Cloudflare ست می‌شن.

## لایسنس

شخصی — بر پایه‌ی Cat Panel.
