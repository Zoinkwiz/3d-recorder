# Third-party notices

- `viewer/Definitions.java` decodes object, NPC, item, body-kit, underlay and
  overlay config bytes the running client provides, following the opcode
  layout of RuneLite's cache loaders
  (<https://github.com/runelite/runelite/tree/runelite-parent-1.13.1/cache/src/main/java/net/runelite/cache/definitions/loaders>).
  Copyright (c) 2016-2017, Adam <Adam@sigterm.info>. Nothing from
  `net.runelite:cache` is bundled.
- `viewer/BodyColors.java` adapts the body-colour palettes of Creator's Kit's
  `KitRecolourer.java` (<https://github.com/ScreteMonge/creators-kit>).
  Copyright (c) 2023, ScreteMonge.
- `viewer/TileGeometry.java`'s thirteen tile masks were checked against the
  `SceneTileModel` format in open-osrs/runelite.
- The Gradle 8.10 wrapper is distributed under the Apache License 2.0
  (<https://www.apache.org/licenses/LICENSE-2.0>).

Both copyright holders above license their code under the BSD 2-Clause License:

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
