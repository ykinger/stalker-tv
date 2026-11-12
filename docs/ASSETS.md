# Demo assets and attribution

All catalogue names and descriptions are fictional demo content. The three geometric poster vectors, app icon, and TV banner were created for this project and do not reproduce third-party app artwork.

## Sample video

**Big Buck Bunny** © 2008 Blender Foundation, licensed under [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).

- Film/project: https://www.bigbuckbunny.org/
- Credits: https://peach.blender.org/about/
- Sample host: https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4

The app bundles a 15-second excerpt (01:00–01:15) from the sample above. It was trimmed and re-encoded to H.264/AAC for compact, offline demo playback. The excerpt is distributed under the same CC BY 3.0 licence, with attribution displayed in the player and Settings. No endorsement by Blender Foundation is implied. Every playable mock entry resolves to this same excerpt; a mock channel is not an actual live stream.

Reproduction using FFmpeg:

```sh
ffmpeg -ss 60 -i BigBuckBunny_320x180.mp4 -t 15 \
  -c:v libx264 -preset veryfast -crf 27 -c:a aac -b:a 64k \
  -movflags +faststart big_buck_bunny_sample.mp4
```
