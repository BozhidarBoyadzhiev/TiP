# Third party notices

Code and assets from others that TiP ships, with the notice each licence asks for.

## QR Code generator library (Java), io.nayuki:qrcodegen 1.8.0

Makes the QR code of a sandbox's "Continue on another device" link on the server (`dev.tip.owners.QrMatrix`).
https://www.nayuki.io/page/qr-code-generator-library

Copyright (c) Project Nayuki. (MIT License)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

- The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
  Software.
- The Software is provided "as is", without warranty of any kind, express or implied, including but not limited to the
  warranties of merchantability, fitness for a particular purpose and noninfringement. In no event shall the authors or
  copyright holders be liable for any claim, damages or other liability, whether in an action of contract, tort or
  otherwise, arising from, out of or in connection with the Software or the use or other dealings in the Software.

## Firmware

The physical panda's firmware (`firmware/`) links the libraries below into the image flashed to the ESP32.

### WebSockets for Arduino, links2004/WebSockets 2.7.1

The device's WebSocket client (`firmware/src/network_task.cpp`), over TiP's own TLS client in
`firmware/src/secure_transport.cpp`. https://github.com/Links2004/arduinoWebSockets

Copyright (c) 2015 Markus Sattler. All rights reserved.

This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General
Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option)
any later version. This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
License for more details: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

TiP uses the library unmodified, at the version pinned in `firmware/platformio.ini`. Its source is at the address
above and the whole firmware source is in this repository, so the image can be rebuilt and relinked with a modified
copy of the library with `pio run`.

### PN532 Type 4 tag emulation, ported from Seeed-Studio/PN532 1.1.0

`firmware/lib/tip_core/src/tip_type4_tag.cpp` and `tip_type4_tag.h` are a port of the library's `emulatetag.cpp`
(Armin Wieser), changed for TiP so READ BINARY is bounded by the selected file and the tag stays read only. The brief
05c spike's `firmware/spikes/pn532/src/type4_tag.h` comes from the same file. https://github.com/Seeed-Studio/PN532

BSD 3-Clause License

Copyright (c) 2012, Adafruit Industries
Copyright (c) 2013, Seeed Technology Inc.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
   disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
   products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### ArduinoJson 6.21.5

Parses the server's messages and builds acks (`firmware/lib/tip_core/src/tip_protocol.cpp`).
https://arduinojson.org

Copyright © 2014-2023, Benoit BLANCHON (MIT License)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

- The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
  Software.
- The Software is provided "as is", without warranty of any kind, express or implied, including but not limited to the
  warranties of merchantability, fitness for a particular purpose and noninfringement. In no event shall the authors or
  copyright holders be liable for any claim, damages or other liability, whether in an action of contract, tort or
  otherwise, arising from, out of or in connection with the Software or the use or other dealings in the Software.

## Elsewhere in the repository

- Fredoka font: SIL Open Font License 1.1, `src/main/resources/static/fonts/fredoka/OFL.txt`.
