# CV Modifier-by Sourov — Android MVP

This repository contains:
- `android/`: native Android app (Kotlin + Jetpack Compose)
- `backend/`: FastAPI service that extracts PDF/DOCX text, asks OpenAI to rewrite it, and returns a DOCX file.

## Important
This is an MVP starter, not a published production app. A public app must call a secured backend; **never embed an OpenAI API key in the APK**. The backend below expects `OPENAI_API_KEY` in its server environment.

The current execution environment does not include Android SDK, Gradle, Flutter, or Android build tools, so an installable APK could not be compiled here. Open `android/` in Android Studio and build a signed APK after configuring the backend URL.

## Run backend
```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY="your-key"
uvicorn main:app --host 0.0.0.0 --port 8000
```
Set `OPENAI_MODEL` if needed (default: `gpt-4.1-mini`).

For a public deployment:
- deploy the backend behind HTTPS
- add authentication, rate limiting, abuse controls, logging minimization, and a privacy policy
- set the Android `BASE_URL` in `android/app/build.gradle.kts`
- configure CORS only if you later add a web client

## Build Android APK
1. Install Android Studio with Android SDK (API 35).
2. Open the `android` folder.
3. Wait for Gradle sync.
4. Update `BASE_URL` in `android/app/build.gradle.kts` to your HTTPS backend URL.
5. Build > Generate Signed Bundle / APK > APK.
6. Keep the signing key private and back it up.

## Supported flow
1. Select a PDF or DOCX resume.
2. Enter editing instructions and optionally paste a job description.
3. App sends the file to the backend.
4. Backend extracts text, asks the AI to preserve factual details and rewrite the CV, then returns a DOCX.
5. App saves the resulting DOCX in Downloads.

## Limitations of this MVP
- PDF text extraction is supported; scanned/image-only PDFs need OCR, which is not included.
- DOCX output is provided; PDF export can be added with a server-side office converter.
- No user accounts, payments, history sync, or production security controls yet.
- Always review generated content for accuracy before applying for a job.

## Optional cloud APK build
The repository includes `.github/workflows/build-apk.yml`. Push the project to a GitHub repository, open Actions → Build ResumeAI debug APK → Run workflow, and provide the HTTPS backend URL. GitHub Actions will produce a test/debug APK as a downloadable workflow artifact. This is not a Play Store release-signed APK.
