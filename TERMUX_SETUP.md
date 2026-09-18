# Termux: رفع Hammam AttendAI إلى GitHub

الهدف من Termux هنا هو فك الملف وتهيئة Git ورفع المشروع. بناء Android محليًا في Termux ليس شرطًا.

```bash
pkg update
pkg install git unzip
termux-setup-storage
cd /sdcard/Download
unzip Hammam-AttendAI.zip
cd Hammam-AttendAI

git init
git add .
git commit -m "Initial Hammam AttendAI build"
git branch -M main
```

أنشئ مستودع GitHub فارغًا باسم `Hammam-AttendAI`، ثم:

```bash
git remote add origin <USER_REPOSITORY_URL>
git push -u origin main
```

بعد الرفع، افتح **GitHub → Actions → Android Build**. عند النجاح ستجد `Hammam-AttendAI-debug-apk` ضمن Artifacts.

لا تضع GitHub token أو مفاتيح WhatsApp أو AI أو البريد في الملفات. استخدم Git credential helper أو GitHub Secrets عند الحاجة.
