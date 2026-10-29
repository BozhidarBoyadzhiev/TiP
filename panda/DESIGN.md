# Why the panda looks like this

[README.md](README.md) says how to build the panda. This page says why it is built that way.

## Three tries

The first plan was a folded low poly panda from one big cardboard template. It looked good on screen, but folding it by hand would have taken about two weeks. The second plan was a 3D print. An online order would have left no time for a second try if the first one came out wrong.

The third plan is the one we built. It is a panda made of plain boxes, because anyone can draw a box with a ruler. It looks a little silly on purpose. A tipping device that people walk up to should look friendly rather than clever.

## The phone has to reach the antenna

The PN532 only answers a phone that is about 2 mm away. In our NFC test a phone through 3 to 4 mm of cardboard never reached it. So the body front has a window cut in it, the board sits in that window level with the outside, and only the paper coin covers it. The board's red power light shows through the coin and marks the spot to tap, which we did not plan but kept.

There is no NFC sticker anywhere on the panda. A sticker would answer the phone instead of the PN532, and the panda would lose its live tap.

Metal near the antenna weakens it, so the power bank stands at the back of the body, about 5 cm behind the board.

## The light is the ESP32's own LED

The panda has no extra LED. The blue LED on the ESP32 board plays each reaction and the red one shows the power is on. That leaves only three parts inside: the PN532, the ESP32 and the power bank.

To use the board's own LEDs the board has to sit behind a window. The head is only 60 mm tall, so the ESP32 lies on its side against the head front with its LEDs behind the nose. The paper nose softens the light into a glow.

## Everything can come out

The back of the body and the back of the head are doors hinged at the bottom with a tuck flap at the top. Every board is held with foam tape or masking tape and never glued. You can charge or swap the power bank, reach the ESP32's USB port and move a board that sits wrong without cutting the panda open. About 5 cm of spare wire between the head and the body lets the head door open fully.

## Thin cardboard

We used thin corrugated cardboard. It is easier to cut cleanly and folds without cracking. It is strong enough for a panda that stands on a desk.

## A prototype

The cardboard panda shows the idea working end to end. A real product could use different parts and a sturdier case, and the backend would not change.
