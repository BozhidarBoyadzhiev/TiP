# Third party notices

Code and assets from others that TiP ships, with the notice each licence asks for. The full text of every licence
named here is in `LICENSES/`.

## QR Code generator library (Java), io.nayuki:qrcodegen 1.8.0

Makes the QR code of a sandbox's "Continue on another device" link on the server (`dev.tip.owners.QrMatrix`).
https://www.nayuki.io/page/qr-code-generator-library

Licence: MIT, `LICENSES/MIT.txt`.

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

The physical panda's firmware (`firmware/`) links the libraries below into the image flashed to the ESP32. The
component list under ESP-IDF comes from the linker map of `pio run -e esp32dev`
(`firmware/.pio/build/esp32dev/firmware.map`): every archive that puts code or data into the image, with the copyright
lines of the source files of the objects actually linked.

### WebSockets for Arduino, links2004/WebSockets 2.7.1

The device's WebSocket client (`firmware/src/network_task.cpp`), over TiP's own TLS client in
`firmware/src/secure_transport.cpp`. https://github.com/Links2004/arduinoWebSockets

Licence: LGPL 2.1 or later, `LICENSES/LGPL-2.1.txt`.

Copyright (c) 2015 Markus Sattler. All rights reserved.

This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General
Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option)
any later version. This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
License for more details: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

TiP uses the library unmodified, at the version pinned in `firmware/platformio.ini`. Its source is at the address
above and the whole firmware source is in this repository, so the image can be rebuilt and relinked with a modified
copy of the library with `pio run`.

### Arduino core for the ESP32, espressif/arduino-esp32 3.3.12

The Arduino framework the firmware is built on (WiFi, serial and `NetworkClientSecure`), from
the pioarduino platform pinned in `firmware/platformio.ini`. https://github.com/espressif/arduino-esp32

Licence: LGPL 2.1 or later, `LICENSES/LGPL-2.1.txt`, for the Arduino API files; Apache 2.0, `LICENSES/Apache-2.0.txt`,
for Espressif's files and `ssl_client.cpp`.

Linked files and their copyright lines:

- LGPL 2.1: Copyright (c) 2005-2013 Arduino Team; Copyright (c) 2011-2014 Arduino (`ETH`); Copyright (c) 2014, 2015
  Ivan Grokhotkov (`WiFi`, `WiFiGeneric`, `WiFiSTA`, `WiFiScan`, `Esp`); Copyright (c) 2015, 2016 Hristo Gochkov
  (`NetworkClient`, `NetworkUdp`, `NetworkClientSecure`, `SPI`); Copyright (C) 2017 Evandro Luis Copercini
  (`NetworkClientSecure`); Copyright (c) 2008 David A. Mellis (`Print`, `Stream`); Copyright (c) 2011 Adrian McEwen
  (`IPAddress`); Copyright (c) 2015 Markus Sattler (`StreamString`); Copyright (c) 2004-06, 2009-10 Hernando Barragan
  (`WMath`, `WString`); Copyright 2011, Paul Stoffregen (`WString`).
- Apache 2.0: Copyright 2015-2026 Espressif Systems (Shanghai) CO LTD (`esp32-hal-*`, `Network*` managers, `STA`,
  `AP`); Copyright (C) 2006-2015, ARM Limited and Copyright (C) 2017 Evandro Luis Copercini (`ssl_client.cpp`).
- Public domain: libb64 (`cencode.c`), http://sourceforge.net/projects/libb64.

This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General
Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option)
any later version. This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
License for more details: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

TiP uses the core unmodified, at the version the pinned platform installs. Its source is at the address above and the
whole firmware source is in this repository, so the image can be rebuilt and relinked with a modified copy of the core
with `pio run`.

### ESP-IDF, espressif/esp-idf 5.5.5 (b774170ff46)

Espressif's IoT Development Framework, linked in as the precompiled libraries of the Arduino core above (the ESP32's
drivers, WiFi, TCP/IP, TLS with the certificate bundle, FreeRTOS). https://github.com/espressif/esp-idf

Licence: Apache 2.0, `LICENSES/Apache-2.0.txt`, for Espressif's own code and the precompiled WiFi, PHY, coexistence
and Bluetooth controller libraries (espressif/esp32-wifi-lib, esp-phy-lib and esp32-bt-lib, which carry the same
licence).

Copyright 2015-2026 Espressif Systems (Shanghai) CO LTD

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0. Unless required by
applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.

TiP uses ESP-IDF unmodified, at the version the pinned platform installs. These third party components of ESP-IDF and
of the toolchain are linked into the image:

#### lwIP 2.2.0 (espressif/esp-lwip fd432e4), `liblwip.a`

The TCP/IP stack: TCP, UDP, IPv4 and IPv6, DHCP, DNS, SNTP, sockets and PPP.

- BSD 3-Clause, `LICENSES/BSD-3-Clause.txt`: Copyright (c) 2001-2004 and 2001-2003 Swedish Institute of Computer
  Science; Copyright (c) 2002-2003, Adam Dunkels; Copyright (c) 2001-2004 and 2003-2004 Axon Digital Design B.V., The
  Netherlands; Copyright (c) 2001-2004 and 2003-2004 Leon Woestenberg; Copyright (c) 2002 CITEL Technologies Ltd;
  Copyright (c) 2007-2009 Frédéric Bernon, Simon Goldschmidt; Copyright (c) 2018 Simon Goldschmidt; Copyright (c) 2010
  Inico Technologies Ltd; Copyright (c) 2016 The MINIX 3 Project. Espressif's port files are Apache 2.0.
- The PPP files carry their own licences, each in `LICENSES/` verbatim:
  - `fsm.c`, `ipcp.c`, `lcp.c`, `magic.c`, `upap.c` and `auth.c`: Copyright (c) 1984-2000 Carnegie Mellon University,
    `LICENSES/LicenseRef-lwIP-PPP-CMU.txt`. **This product includes software developed by Computing Services at
    Carnegie Mellon University (http://www.cmu.edu/computing/).**
  - `auth.c`: Copyright (c) 1993-2002 Paul Mackerras, `LICENSES/LicenseRef-lwIP-PPP-Mackerras.txt`. **This product
    includes software developed by Paul Mackerras <paulus@samba.org>.**
  - `ppp.c` and `magic.c`: Copyright (c) 2003 by Marc Boucher, Services Informatiques (MBSI) inc.; portions Copyright
    (c) 1997 and 1998 by Global Election Systems Inc., `LICENSES/LicenseRef-lwIP-PPP-MBSI-GES.txt`.
  - `ppp.c`: Copyright (c) 1994 The Australian National University, `LICENSES/LicenseRef-lwIP-PPP-ANU.txt`.
  - `ppp.c`: Copyright (c) 1989 Carnegie Mellon University, `LICENSES/LicenseRef-lwIP-PPP-CMU-1989.txt`. This software
    was developed by Carnegie Mellon University.
  - `vj.c`: Copyright (c) 1989 Regents of the University of California, `LICENSES/LicenseRef-lwIP-PPP-UCB-1989.txt`.
    This software was developed by the University of California, Berkeley.

#### Mbed TLS 3.6.6 (Mbed-TLS/mbedtls 9d669ea), `libmbedcrypto.a`, `libmbedx509.a`, `libmbedtls_2.a`

TLS, X.509 and the cryptography under them. Mbed TLS is offered under Apache 2.0 or GPL 2.0 or later; TiP uses it
under Apache 2.0, `LICENSES/Apache-2.0.txt`. Copyright The Mbed TLS Contributors. Espressif's hardware acceleration
and certificate bundle files are Apache 2.0, Copyright Espressif Systems (Shanghai) CO LTD.

#### FreeRTOS Kernel V10.5.1, ESP-IDF SMP version, `libfreertos.a`

The scheduler, queues, timers and event groups. MIT, `LICENSES/MIT.txt`: Copyright (C) 2020, 2021 Amazon.com, Inc. or
its affiliates. The Xtensa port (`port.c`, `xtensa_init.c`) is MIT, Copyright (c) 2015-2019 Cadence Design Systems,
Inc.; Espressif's additions are Apache 2.0.

#### Xtensa runtime and HAL, `libxtensa.a`, `libxt_hal.a`

Interrupt and context handling for the ESP32's Xtensa cores. MIT, `LICENSES/MIT.txt`: Copyright (c) 1999-2015 and
2015-2019 Cadence Design Systems, Inc.; Espressif's changes are Apache 2.0.

#### wpa_supplicant (hostap, in ESP-IDF's `components/wpa_supplicant`), `libwpa_supplicant.a`

WPA2 and WPA3 authentication. BSD 3-Clause, `LICENSES/BSD-3-Clause.txt` (a few files are offered under GPL 2.0 or the
BSD licence; TiP uses the BSD licence): Copyright (c) 2002-2019, Jouni Malinen <j@w1.fi> and contributors; Copyright
(c) 2019, 2020, The Linux Foundation; Copyright (c) 2013 Cozybit, Inc. `fastpbkdf2.c` is dedicated to the public
domain by Joseph Birr-Pixton (2015) under CC0 1.0, `LICENSES/CC0-1.0.txt`. Espressif's glue files are Apache 2.0.

#### TLSF (espressif/tlsf 2867f68), in `libheap.a`

The heap allocator. BSD 3-Clause, `LICENSES/BSD-3-Clause.txt`: Copyright (c) 2006-2016 Matthew Conte.

#### cJSON 1.7.19 (DaveGamble/cJSON b2890c8), `libjson.a`

MIT, `LICENSES/MIT.txt`: Copyright (c) 2009-2017 Dave Gamble and cJSON contributors.

#### newlib C library, `libc.a` of the xtensa-esp-elf toolchain 14.2.0+20260121

The C library (formatted output, strings, time, locale). newlib is a collection of permissive licences, all in
`LICENSES/newlib.txt` (the toolchain's `COPYING.NEWLIB`).

#### GCC runtime, `libgcc.a` and `libstdc++.a` of the same toolchain

GPL 3.0 with the GCC Runtime Library Exception 3.1, `LICENSES/GPL-3.0.txt` and `LICENSES/GCC-exception-3.1.txt`:
Copyright (C) Free Software Foundation, Inc. The exception allows the compiled firmware to be distributed under any
terms.

### PN532 Type 4 tag emulation, ported from Seeed-Studio/PN532 1.1.0

`firmware/lib/tip_core/src/tip_type4_tag.cpp` and `tip_type4_tag.h` are a port of the library's `emulatetag.cpp`
(Armin Wieser), changed for TiP so READ BINARY is bounded by the selected file and the tag stays read only. The brief
05c spike's `firmware/spikes/pn532/src/type4_tag.h` comes from the same file. https://github.com/Seeed-Studio/PN532

Licence: BSD 3-Clause, `LICENSES/BSD-3-Clause.txt`.

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

Licence: MIT, `LICENSES/MIT.txt`.

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

- Fredoka font: SIL Open Font License 1.1, `src/main/resources/static/fonts/fredoka/OFL.txt`, kept with the font files
  as the licence asks.
