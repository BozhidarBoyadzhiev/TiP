-- Brief 09, finding 09-F013: when TiP first asked PayPal to capture a tip's order. Only a tip with an attempt can
-- have been paid, so only such a tip is looked up at PayPal before it is marked abandoned. Nullable, so the
-- previous release keeps working while this runs: it never writes the column.
ALTER TABLE tip ADD COLUMN capture_attempted_at timestamptz;
