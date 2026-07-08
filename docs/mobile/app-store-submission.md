# App Store Submission Checklist

## App Privacy (App Store Connect)
- Data type: **Location**
  - Used for: App functionality
  - Linked to user: No (unless account system is introduced)
  - Tracking: No
- Data type: **Diagnostics** (only if crash/reporting SDK is added)

## Permission Copy Consistency
- iOS `Info.plist` `NSLocationWhenInUseUsageDescription` must match store description.
- In-app error/permission prompts should explain fallback path (manual city search).

## Metadata
- App name: FireSky
- Subtitle: Local Sunrise & Sunset Forecast
- Category: Weather
- Age rating: complete questionnaire in App Store Connect.
- Support URL: required.
- Privacy policy URL: required (host published version of template).

## Assets
- iPhone screenshots (6.7", 6.5", 5.5" recommended sets)
- App icon (1024x1024, no alpha)
- Optional preview video

## TestFlight
- Upload build from Xcode Organizer.
- Internal testers first, then external testers.
- Verify onboarding, location permission, and offline behavior before review submission.
