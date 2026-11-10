# OpenWatch Privacy Policy

Last updated: November 9, 2026

OpenWatch is a local-first Android app that pairs a Garmin or Wear OS watch and a Xiaomi body composition scale with your phone and writes what they record to Health Connect.

This policy applies to the OpenWatch phone app and the OpenWatch Wear OS app, both published under the package name `tech.mmarca.openwatch` by Mittetulundusühing Hale (MTÜ Hale), a non-profit association registered in Estonia, registry code 80677610.

Privacy contact: hello@hale.ngo

## Summary

OpenWatch is designed to keep health data on your device.

- No account is required.
- No health data is uploaded to OpenWatch servers. There are none.
- The phone app has no internet permission. Android itself stops it from using the network.
- No ads are shown.
- No analytics SDKs are included.
- No health data is sold or shared with third parties.
- Data travels only over Bluetooth, between your watch or scale and your phone, and inside the phone to apps you connect (see "Apps You Connect On Your Phone").
- OpenWatch writes to Health Connect only what a device you paired recorded, and only after you grant the relevant Android permissions.
- OpenWatch reads from Health Connect only the few things a sync needs, listed below.

## Devices OpenWatch Talks To

OpenWatch uses Bluetooth to reach the devices you add in the app, and nothing else:

- A Garmin watch, over Garmin's own Bluetooth protocol, after you pair it in the app. Android's companion device pairing lets the app notice the watch coming into range so a sync can run on its own.
- A Wear OS watch running the OpenWatch watch app, over Bluetooth. The first time a phone connects, the watch asks you to allow or block it; an allowed phone's token is kept on the watch, and no other phone is answered.
- A Xiaomi Body Composition Scale S400 or S400 Pro. The scale broadcasts each weigh-in; OpenWatch decodes the broadcast with the key you take from your Xiaomi account and type into the app. The key is stored in the app's private preferences and is used for nothing else.

The app asks for Bluetooth and, on older Android versions, location permissions because Android requires them to scan for and connect to Bluetooth devices. OpenWatch does not use location for anything else and keeps no location history.

## What OpenWatch Writes To Health Connect

What a paired device recorded, when you sync it or when a sync runs on a schedule you chose:

- From a Garmin watch: activities with their route and sensor samples, steps, distance, calories, heart rate, resting heart rate, heart rate variability, oxygen saturation, respiratory rate, VO2 max, basal metabolic rate, sleep, and the weight a Garmin scale relays through the watch.
- From a Wear OS watch: heart rate, steps (with their cadence and an estimate of the calories walking cost), floors and elevation gained recorded on the watch, an estimated sleep session per night, and the night's resting heart rate.
- From the scale: weight and heart rate as the scale measured them, and the body fat, lean mass and body water the app estimates from the scale's measurement and your body profile.

Each record names OpenWatch as its source. Records OpenWatch wrote can be deleted from Health Connect's own settings at any time. Records written by other apps are never changed.

## What OpenWatch Reads From Health Connect

OpenWatch is not a dashboard; it shows no history. It reads, with your permission:

- Your weight and height, to estimate body composition from the scale and to let the Wear OS watch app estimate calories.
- Steps, heart rate, resting heart rate, sleep and exercise sessions, only to avoid writing a record a sync already wrote, and to send the watch what it needs for its own display.

Background reading is used only for a scheduled sync.

## Watch Features On The Phone

If you switch them on, and only then:

- **Notifications.** With notification access granted, OpenWatch forwards the notifications you choose to a Garmin watch. They are held in memory while they are shown and are never written to storage or sent anywhere else.
- **Calendar.** With calendar permission granted, upcoming events are sent to a Garmin watch's calendar view. Nothing is stored.
- **Music.** The title, artist and album of what your phone is playing are sent to the watch for its music controls. Nothing is stored.
- **Navigation.** If you use the CoMaps app, its turn-by-turn guidance can be shown on a Garmin watch. OpenWatch reads the guidance from CoMaps and passes it on; it keeps none of it.
- **Weather.** Weather another app broadcasts on the phone can be passed to the watch. OpenWatch fetches no weather itself.
- **Find my watch, alarms, watch settings, saved locations.** Sent to the watch at your request.

## Local Storage

Health Connect is the source of truth for every record it has a type for. OpenWatch also keeps, in its private storage on your device:

- App preferences: the paired devices, the sync schedule, the notification apps you chose, your body profile, the scale's key, and the acknowledged prompts.
- A small database of the watch measurements Health Connect has no record type for (such as stress, Body Battery and sleep scores from a Garmin watch), shown on the Watch Data screen.
- Sync cursors and file watermarks, so a sync fetches only what is new.

The Wear OS watch app keeps the sensor readings it has not yet synced in its own private storage on the watch, and drops them once the phone has them.

OpenWatch opts out of Android's cloud backup. When you move to a new phone, Android can copy the app's data across. The phone app's manifest removes inherited Android network permissions, including `INTERNET`.

## Diagnostics

OpenWatch sends no reports by itself. Its logs hold counts, types and reasons, never a value, a record's time, a name or a device address.

## Apps You Connect On Your Phone

Other apps on the same phone can ask to use OpenWatch through OpenLink, an interface that
works only inside the phone. OpenVitals is the first. Nothing travels over a network.

- OpenVitals, signed by the same developer, is connected without asking. Any other app
  waits until you allow it on OpenWatch's Connected apps screen, and you can block or
  forget an app, or switch off any one thing it may do, at any time.
- What a connected app can be allowed: your body profile (birth year, sex, height,
  weight), shared both ways so you enter it once; the list of your watches and scale with
  their battery and last sync, and a request to sync one; keeping your watch streaming while
  the app records an activity; and the watch's heart rate and steps while it does.
- Live readings go only to the app that asked for them, while its recording runs, and are
  not stored by OpenWatch. While they flow, OpenWatch shows a notification naming the app. Notifications, calendar events, music, locations and the scale's
  key are never shared.

## Data Sharing

OpenWatch does not sell, rent, transfer, or share your health data with third parties.

OpenWatch does not include advertising SDKs or analytics SDKs.

## Data Retention And Deletion

Health data in Health Connect is controlled by your device and Health Connect settings. Records OpenWatch wrote there stay after you uninstall the app; you can delete them from Health Connect's own settings at any time.

Removing a watch or the scale in the app deletes its local data and forgets its pairing. You can revoke OpenWatch permissions at any time from Android Settings or Health Connect settings. You can delete all local OpenWatch data by clearing the app's storage in Android settings or uninstalling the app.

## Security

OpenWatch relies on Android and Health Connect permission controls to protect access to health data. Its local data sits in app-private storage, which other apps cannot read. The link to a Wear OS watch runs over a paired, encrypted Bluetooth connection and answers only the phone you allowed on the watch.

Because OpenWatch works locally and does not upload health data, there is no OpenWatch cloud account or cloud health-data store.

## Children

OpenWatch is not directed to children and is not designed for child-directed use.

## Changes To This Policy

This privacy policy may be updated when OpenWatch changes how it accesses, uses, stores, or shares data. The "Last updated" date above will be changed when this policy is updated.

## Contact

For privacy questions, contact:

hello@hale.ngo
