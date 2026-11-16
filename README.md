# ObsiLearn

Watch your video courses from wherever they are, highlight what the teacher says, and keep it in Obsidian.

ObsiLearn is part of the Obsi apps (ObsiTube, ObsiRead, Obsi Bible). They share one core, [obsi-core](https://github.com/Virus7976/obsi-core): the same Obsidian setup, highlights you can see from every app, one review, and links that open in the right app.

## Where your courses can be

- A folder on the phone, an SD card, a USB stick, or a cloud app that shows its folders in the file picker
- Google Drive (sign in with Google)
- A NAS or cloud with WebDAV: Synology, QNAP, Nextcloud, ownCloud, pCloud, Koofr and more
- A Windows or NAS shared folder (SMB)
- The Imperium desktop hub on a PC
- A web page that lists files
- A podcast or RSS feed
- Links to video or audio files

Any layout works: one folder of videos, a folder per module, or a folder of many courses. Files that share a number ("03. Intro.mp4", "03. Intro.pdf") are one lesson, and a subtitle file with the video's name is its transcript.

## What you can do

- Pick up where you left off, in every course
- Save a lesson, a module or a whole course for offline: the full video, or audio only
- Make transcripts for lessons that have none: on the phone (free, offline) or with your own Groq or OpenAI key
- Highlight the transcript and save it to Obsidian with your templates
- Keep each course's progress as one note in your vault, so it syncs with the rest of your vault

## Build

JDK 21. The shared core is the `core/` submodule; on a PC with `../obsi-core` next to this folder, that copy is used.

```
./gradlew :app:assembleDebug
```
