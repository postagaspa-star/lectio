# Lectio

Lectio turns a student's notes and past graded tests into audio lessons that sound like a lesson rather
than a podcast. One teacher voice explains the topic in blocks of four to six minutes, stops after each block
to ask a question, waits for a written answer and corrects it, and closes with a mock oral exam in the style
of that specific teacher. Before preparing a lesson the student picks the kind (full explanation, quick review
with many questions, or a mock oral exam only), which notes and tests to use (the server suggests the ones that
match the topic), and can add free-form instructions such as "focus on dates and names".

The calibration comes from the tests themselves. The student photographs a graded test, asks Gemini to
transcribe it with a fixed template (question, own answer, the teacher's corrections, points), and drops the
file into the subject. The server reads every test of that subject and keeps a "teacher profile": recurring
topics, types of question, what gets marked down and what gets rewarded. Every lesson of that subject is
written against the profile, and the questions answered badly come back in later lessons.

## How it is built

- **Server** (`server/`): FastAPI and SQLite in Docker on a home NAS with a low-power CPU and no GPU, so the
  writing happens at the providers. The server reads the files (txt, md, pdf, docx, odt, rtf, images), asks
  Mistral for the lesson plan and script, asks Mistral Voxtral (expressive voice) or one of the free local
  voices for the audio, and assembles a single MP3 with ffmpeg, keeping chapter and question timestamps.
- **Free voices** (`voci/`): a small internal service on the same NAS, with no port open to the outside,
  that reads Italian on the CPU with no internet and no cost: Kyutai Pocket TTS (Italian model, normal and
  24-layer) and Supertonic 3 (male voice M1). It is slower than the paid voice, so the apps say how long a
  lesson will take to prepare with each voice.
- **Android app** (`android/`): Kotlin and Jetpack Compose with a Media3 player that pauses at each question,
  works from the lock screen and headphones, and plays downloaded lessons offline.
- **Windows app** (`pc/`): .NET 8 WinForms hosting a WebView2 interface, with drag and drop for files and
  signed self-updates.
- **Docs** (`docs/`): the API contract shared by the three parts, the design guide and the clickable
  prototype. The interface and the docs are in Italian because the app is used in an Italian school.

## Money

The whole tool is meant to cost about 2 euros a month. A monthly cap is checked in one place on the server
before every paid request, including retries and lessons already queued, and the estimated cost is shown
before a lesson is prepared. When the expressive voice would exceed the cap, lessons fall back to a free
voice on the NAS. Without a Mistral key the server runs in a test mode with a fake writer and a robotic local voice, so
everything can be tried at no cost.

## Who did what

Andrea made the product and design decisions (written answers instead of listening only, mandatory or
skippable questions per lesson, the material living in the subject rather than in each lesson, choosing the
material and the kind of lesson, his own instructions for each lesson, which free voices to keep after
listening to samples) and tests the
apps in real use. The code was written with Claude Code, which also ran adversarial reviews of each part
before release.

All names and data in the examples and tests are made up. No keys or network addresses are stored in this
repository: they live in local files that git ignores.

## Third-party models

- Kyutai Pocket TTS (https://github.com/kyutai-labs/pocket-tts): code under the MIT licence, model weights
  under CC-BY 4.0, by Kyutai. The Italian voice "Giovanni" is the one shipped with it, made from a Mozilla
Common Voice clip (CC0).
- Supertonic 3 (https://github.com/supertone-inc/supertonic): code under the MIT licence, model under the
  OpenRAIL-M licence, by Supertone.
