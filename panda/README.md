# Build the panda

The panda is nine cardboard boxes glued together. Each box starts as one flat shape (a net) that you draw on cardboard with a ruler, cut out, fold and glue. You need no printer for the boxes and no special tools.

<!-- PHOTO: images/finished.jpg. The finished panda from the front at a slight angle, lit, on a plain background. -->

![The panda as a 3D model](images/panda-3d.jpg)

The model above is the plan. Ours looks a bit scuffed, which is fine. Why the panda looks like this is in [DESIGN.md](DESIGN.md). The wiring and the firmware are in [firmware/README.md](../firmware/README.md).

## What you need

- Thin corrugated cardboard, like the walls of a delivery box. Thin card is much easier to cut and fold than thick card.
- A pencil, a metal ruler and a craft knife, plus a cutting mat or an old board to cut on.
- Glue that holds cardboard.
- A printer for one A4 page of panel art.
- Double sided foam tape and masking tape for the boards.
- The electronics from [firmware/README.md](../firmware/README.md): an ESP32 DevKit, a PN532 board, a power bank and a USB to 2 pin lead.

Ours took about a week of spare evenings.

## The boxes

Every size is the outside size in mm, as width (side to side) x height x depth (front to back).

| Box | How many | W x H x D | Where it goes |
|---|---|---|---|
| Body | 1 | 109 x 105 x 80 | The middle. The PN532 sits behind the coin on its front. |
| Head | 1 | 109 x 60 x 83 | On the body, fronts level. The ESP32 sits behind the nose. |
| Leg | 2 | 34 x 36 x 80 | Under the body, front edges level with the body front, centres 38 either side of the middle. |
| Arm | 2 | 24 x 72 x 34 | On the body sides, 6 down from the top and 6 back from the front. |
| Ear | 2 | 26 x 22 x 14 | On the head top, centres 42 either side of the middle, 34 back from the face. |
| Tail | 1 | 22 x 22 x 14 | On the body door, centred, 11 above the body bottom. |

The body and the head are made from two nets each. Net A is the sides, the front and the top. Net B is the door and the bottom. Every other box is one net.

The window positions are exact on purpose. The PN532 window and the nose window line up with the boards behind them, and the two neck holes line up so the wires can pass from the body into the head.

## Draw the nets

![Every net with its measurements](images/nets.png)

The image has every net with every measurement. This is how we drew ours:

1. Draw a straight edge along the cardboard. Mark the gold numbers along it. They are running distances from the first corner, so you never add sizes up in your head.
2. Do the same down the side, then join the marks with the ruler into panels. The size inside each panel is width x height.
3. Add the glue tabs on the edges shown in grey. They are 15 mm deep, or 10 mm on the ears and the tail. Cut their corners off at an angle so they fold in without catching.
4. Draw the windows and the neck holes where the net says.
5. Cut on the solid lines and cut the windows and holes out.
6. Press every dashed line along the ruler with the back of the knife or a dry pen. Card folds cleanly on a pressed line and cracks without one.

<!-- PHOTO: images/step-1-drawn.jpg. An arm net drawn on the cardboard with the ruler next to it. -->
<!-- PHOTO: images/step-2-cut.jpg. The arm net cut out with its fold lines pressed. -->

## Fold and glue

1. Fold every pressed line inwards.
2. Glue one tab at a time to the inside of the panel it meets and hold it until it sets.
3. On the small boxes glue the long side tab first, then close each end.
4. For the body and the head, glue the bottom of net B inside net A. Never glue the door. It is hinged at the bottom and its top flap tucks in under the top panel like a cereal box.

<!-- PHOTO: images/step-3-glued.jpg. The arm folded into a box with its tabs glued. -->

## Panel art

[panel-art.pdf](panel-art.pdf) is one A4 page with the coin and shoulder band for the body front, the face for the head front and the paw pads for the leg fronts.

1. Print it at 100% ("Actual size", not "Fit to page").
2. Check that the 50 mm bar on the page really measures 50 mm.
3. Cut the pieces out and glue each one onto its panel. The coin covers the PN532 window and the pink nose covers the nose window.

## Put the electronics in

- **PN532 behind the coin.** Push the board into the body front window from inside, antenna side out and level with the outside, and tape it in from inside. Only the paper coin is between the phone and the antenna. The board's red power light shows through the paper as a dot, which marks the spot to tap.
- **ESP32 behind the nose.** The head is only 60 mm tall, so the board lies on its side. Stick it to the inside of the head front with foam tape, chips facing forward and the USB end to one side. Slide it until its LEDs sit behind the nose window, then press the tape down. Red means on and blue plays each tip reaction.
- **Power bank at the back.** Stand it upright on the body floor against the door, ports up. That keeps it about 5 cm behind the antenna.
- Leave about 5 cm of spare wire between the head and the body so the head door opens fully.
- Use only tape. Nothing is glued to a board, so everything can come out again.

![See-through view of where the boards go](images/panda-3d-inside.jpg)

<!-- PHOTO: images/inside.jpg. The back of the panda with both doors open, showing the power bank and the ESP32. -->

## Put it together

1. Test a phone tap with the PN532 taped in before the head goes on. See "Build, flash, monitor, test" in [firmware/README.md](../firmware/README.md).
2. Glue the legs under the body.
3. Glue the arms to the body sides.
4. Glue the head on with the fronts level and the neck holes lined up.
5. Glue the ears on the head and the tail on the body door.

<!-- PHOTO: images/step-4-attached.jpg. The arm glued to the body side. -->

Tap the coin with a phone. The tip page should open, and after a tip the nose lights up with the reaction.

## Print the nets instead

If you would rather print than draw, [nets-print.pdf](nets-print.pdf) has the same nets on six A4 landscape sheets with the panel art already on them. Print on card of 200 to 250 gsm at 100%, or print on paper and glue each sheet onto thin cardboard before cutting. The printed body has no PN532 window, so tape the board behind the printed coin. We drew our panda by hand, so this route is untested.

## Credits

Bozhidar built the panda with help from [@Edilini05](https://github.com/Edilini05).
