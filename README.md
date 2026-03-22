# MealPrepAI Android App

GitHub-first Android app that lets you:
- take a picture of food
- pick a food photo from gallery
- get meal prep ideas from an AI vision API

## What you need
- A GitHub repo
- GitHub Actions enabled
- Your own AI API key
- Optional: your own endpoint/model if you are not using the default values in the app

## Default app settings
Inside the app, open **Settings** and enter:
- **API Key**
- **Endpoint**
- **Model**
- **Goal** (fat loss, high protein, cheap meals, bulk prep, etc.)

The default endpoint in the app is:
`https://api.openai.com/v1/chat/completions`

The default model is:
`gpt-4.1-mini`

You can change both inside the app if your provider or model is different.

## Upload to GitHub
Upload the whole folder structure, including:
- `app/`
- `.github/workflows/android.yml`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`

## Build on GitHub
Open the **Actions** tab and run **Android APK Build**.
When it finishes, download the artifact named **mealprep-debug-apk**.

## Notes
This project is designed to build in GitHub Actions. It does not require Android Studio to exist as a project.
