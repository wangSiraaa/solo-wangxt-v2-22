package com.park.energy.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "import_batch")
public class ImportBatch {
    @Id
    private String id;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "content_sha256", nullable = false, unique = true)
    private String contentSha256;
    @Column(name = "row_count", nullable = false)
    private int rowCount;
    @Column(name = "imported_rows", nullable = false)
    private int importedRows;
    @Column(name = "duplicate_rows", nullable = false)
    private int duplicateRows;
    @Column(name = "invalid_rows", nullable = false)
    private int invalidRows;
    @Column(columnDefinition = "text")
    private String errors;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportBatch() {}

    public ImportBatch(String id, String fileName, String contentSha256, int rowCount, int importedRows,
                       int duplicateRows, int invalidRows, String errors, Instant createdAt) {
        this.id = id;
        this.fileName = fileName;
        this.contentSha256 = contentSha256;
        this.rowCount = rowCount;
        this.importedRows = importedRows;
        this.duplicateRows = duplicateRows;
        this.invalidRows = invalidRows;
        this.errors = errors;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getFileName() { return fileName; }
    public String getContentSha256() { return contentSha256; }
    public int getRowCount() { return rowCount; }
    public int getImportedRows() { return importedRows; }
    public int getDuplicateRows() { return duplicateRows; }
    public int getInvalidRows() { return invalidRows; }
    public String getErrors() { return errors; }
    public Instant getCreatedAt() { return createdAt; }
}
