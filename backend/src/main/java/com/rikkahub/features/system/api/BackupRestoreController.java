package com.rikkahub.features.system.api;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.rikkahub.features.system.application.BackupRestoreService;
import com.rikkahub.features.system.application.dto.BackupArchiveInfo;
import com.rikkahub.features.system.application.dto.RestoreResult;
import com.rikkahub.platform.http.ApiFailure;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for consistent snapshot backup and disaster recovery.
 */
@RestController
@RequestMapping("/api/v1/system")
public class BackupRestoreController {
    private final BackupRestoreService backupRestoreService;

    public BackupRestoreController(BackupRestoreService backupRestoreService) {
        this.backupRestoreService = backupRestoreService;
    }

    @PostMapping("/backup")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupArchiveInfo createBackup() throws IOException {
        return backupRestoreService.createBackup();
    }

    @GetMapping("/backups")
    public List<BackupArchiveInfo> listBackups() {
        return backupRestoreService.listBackups();
    }

    @GetMapping("/backups/{filename}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String filename) {
        try {
            Path file = backupRestoreService.getBackupFile(filename);
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName().toString() + "\"")
                    .contentLength(Files.size(file))
                    .body(resource);
        } catch (FileNotFoundException e) {
            throw new ApiFailure(404, "BACKUP_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiFailure(400, "INVALID_FILENAME", e.getMessage());
        } catch (IOException e) {
            throw new ApiFailure(500, "IO_ERROR", "Failed to access backup file: " + e.getMessage());
        }
    }

    public record RestoreRequest(String filename) {}

    @PostMapping("/restore")
    public RestoreResult restore(@RequestBody RestoreRequest request) {
        if (request == null || request.filename() == null || request.filename().isBlank()) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Backup filename must be provided in request body");
        }
        try {
            return backupRestoreService.restoreFromLocalBackup(request.filename().trim());
        } catch (FileNotFoundException e) {
            throw new ApiFailure(404, "BACKUP_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiFailure(400, "INVALID_BACKUP", e.getMessage());
        } catch (SecurityException e) {
            throw new ApiFailure(400, "CORRUPTED_OR_MALICIOUS_BACKUP", e.getMessage());
        } catch (Exception e) {
            throw new ApiFailure(500, "RESTORE_FAILED", "Restore operation failed: " + e.getMessage());
        }
    }
}
