package com.rosterguard.benchmark;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Generates synthetic benchmark datasets for performance, batching and stress testing.
 * Writes directly to disk using buffered streaming so memory footprint is negligible.
 */
public final class BenchmarkDataGenerator {

    public static final String HEADER =
            "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n";

    private BenchmarkDataGenerator() {}

    /**
     * Create a synthetic CSV file with {@code rowCount} valid employee records.
     *
     * @param file target destination file
     * @param rowCount number of data rows to generate
     * @param version source version for generated rows
     * @param timestamp source updated timestamp (e.g. "2024-01-15T10:00:00Z")
     */
    public static void generateCsv(File file, int rowCount, long version, String timestamp) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024)) {
            out.write(HEADER.getBytes(StandardCharsets.UTF_8));
            byte[] blrBytes = ",,BLR,MORNING\n".getBytes(StandardCharsets.UTF_8);
            byte[] phoneBytes = ",+919000000001".getBytes(StandardCharsets.UTF_8);

            for (int i = 1; i <= rowCount; i++) {
                String line = String.format("EMP%06d,%d,%s,Employee %d,emp%d@example.com",
                        i, version, timestamp, i, i);
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write(phoneBytes);
                out.write(blrBytes);
            }
            out.flush();
        }
    }

    /**
     * Create a CSV file with a specified percentage of malformed rows and duplicate IDs.
     */
    public static void generateCorruptedCsv(File file, int rowCount, double malformedRatio, double duplicateRatio) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024)) {
            out.write(HEADER.getBytes(StandardCharsets.UTF_8));

            int malformedCount = (int) (rowCount * malformedRatio);
            int duplicateCount = (int) (rowCount * duplicateRatio);

            for (int i = 1; i <= rowCount; i++) {
                String empId = String.format("EMP%06d", i);
                // Introduce duplicate ID by reusing previous ID
                if (i <= duplicateCount && i > 1) {
                    empId = String.format("EMP%06d", i - 1);
                }

                String email = "emp" + i + "@example.com";
                String phone = "+919000000001";
                // Introduce malformed row (e.g. invalid phone, bad email)
                if (i > rowCount - malformedCount) {
                    phone = "invalid-phone";
                    email = "not-an-email";
                }

                String line = String.format("%s,1,2024-01-15T10:00:00Z,Employee %d,%s,%s,,BLR,MORNING\n",
                        empId, i, email, phone);
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }
}

