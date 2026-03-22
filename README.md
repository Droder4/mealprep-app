# Meal Prep AI Android App

A GitHub-first Android app that lets you:
- take a photo of food or choose one from the gallery
- send it to a vision-capable AI endpoint
- get meal prep ideas, shopping add-ons, storage tips, and batch-cooking steps

## Important
This app needs an AI API key to analyze food photos. The source code does **not** include one.

## What is included
- Kotlin + Jetpack Compose Android app
- camera and gallery image input
- settings screen for API key, endpoint, model, and meal-prep goal
- GitHub Actions workflow to build a debug APK

## Use GitHub only
You said you want this made with GitHub, not Android Studio. This repo is set up so you can:
1. create a GitHub repo
2. upload these files
3. let GitHub Actions build the APK for you
4. download the APK from the Actions artifacts

## Build on GitHub
Push the project to GitHub, then use the included workflow in `.github/workflows/android.yml`.

When the workflow finishes, open the run and download the `MealPrepAI-debug-apk` artifact.

## First run in the app
Open **Settings** inside the app and add:
- your API key
- your API endpoint
- your model name

Defaults currently point to:
- endpoint: `https://api.openai.com/v1/responses`
- model: `gpt-4.1-mini`

## Notes
- The network request format is set up for a Responses-style vision endpoint.
- If your provider uses a different payload shape, update `MealPrepRepository.analyzePhoto()` in `MainActivity.kt`.
