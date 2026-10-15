-- Brief 09: a tip PayPal confirmed after it was marked FAILED or ABANDONED, hours later at the earliest. It is
-- recorded and classified like any capture, but the panda never plays it: nobody standing there tipped just now.
-- Written by the conditional capture update. A default, so the previous release keeps working while this runs.
ALTER TABLE tip ADD COLUMN revived boolean NOT NULL DEFAULT false;
