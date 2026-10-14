package dev.tip.owners;

import io.nayuki.qrcodegen.QrCode;
import java.util.ArrayList;
import java.util.List;

/**
 * The modules of a QR code with error correction level medium, made on the server so the page needs no QR library:
 * {@code size} rows of {@code size} characters, {@code 1} for a dark module and {@code 0} for a light one, top row
 * first. The quiet zone around it is the page's to draw.
 */
record QrMatrix(int size, List<String> rows) {

    QrMatrix {
        rows = List.copyOf(rows);
    }

    static QrMatrix of(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        List<String> rows = new ArrayList<>(qr.size);
        for (int y = 0; y < qr.size; y++) {
            StringBuilder row = new StringBuilder(qr.size);
            for (int x = 0; x < qr.size; x++) {
                row.append(qr.getModule(x, y) ? '1' : '0');
            }
            rows.add(row.toString());
        }
        return new QrMatrix(qr.size, rows);
    }
}
