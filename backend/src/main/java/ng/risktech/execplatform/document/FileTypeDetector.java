package ng.risktech.execplatform.document;

import ng.risktech.execplatform.common.ApiException;

import java.util.Arrays;
import java.util.Locale;

final class FileTypeDetector {
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private FileTypeDetector() {
    }

    static Detected detect(byte[] header, String filename) {
        Detected detected;
        if (startsWith(header, PDF)) detected = new Detected("pdf", "application/pdf");
        else if (startsWith(header, JPEG)) detected = new Detected("jpg", "image/jpeg");
        else if (startsWith(header, PNG)) detected = new Detected("png", "image/png");
        else throw ApiException.unsupportedType("Only PDF, JPG and PNG files are accepted");

        String extension = extensionOf(filename);
        boolean matches = extension.equals(detected.extension())
                || (detected.extension().equals("jpg") && extension.equals("jpeg"));
        if (!matches) throw ApiException.unsupportedType("The file extension does not match the file content");
        return detected;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    record Detected(String extension, String contentType) {
    }
}
