package ng.risktech.execplatform.document;

import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.consent.ConsentService;
import ng.risktech.execplatform.document.DocumentDtos.DocumentView;
import ng.risktech.execplatform.document.DocumentDtos.Download;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("CV", "ID", "CERTIFICATE");
    private static final Set<String> ID_TYPES = Set.of("NIN", "DRIVERS_LICENSE", "PASSPORT", "VOTERS_CARD");
    private static final String COLUMNS =
            "id, type, original_name, content_type, size_bytes, uploaded_at, identification_type, certification_name";
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final RowMapper<DocumentView> VIEW = (rs, n) -> new DocumentView(
            rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("original_name"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getObject("uploaded_at", OffsetDateTime.class),
            rs.getString("identification_type"), rs.getString("certification_name"));
    private final JdbcTemplate db;
    private final AuditRecorder audit;
    private final ConsentService consent;
    private final Path root;

    public DocumentService(JdbcTemplate db, AuditRecorder audit, ConsentService consent,
                           @Value("${app.upload-dir:./data/uploads}") String uploadDir) {
        this.db = db;
        this.audit = audit;
        this.consent = consent;
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(8);
        } catch (IOException e) {
            throw ApiException.badRequest("Unable to read file");
        }
    }

    static String sanitiseName(String name) {
        if (name == null || name.isBlank()) return "document";
        String base = Path.of(name.replace('\\', '/')).getFileName().toString();
        String safe = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return safe.length() > 200 ? safe.substring(safe.length() - 200) : safe;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", path.getFileName());
        }
    }

    @Transactional
    public DocumentView upload(AuthenticatedUser user, String type, String identificationType,
                               String certificationName, MultipartFile file) {
        consent.requireConsent(user.id());
        if (!TYPES.contains(type)) throw ApiException.badRequest("Unsupported document type");
        if (type.equals("ID") && !ID_TYPES.contains(identificationType))
            throw ApiException.badRequest("Choose a valid identification type");
        if (type.equals("CERTIFICATE") && (certificationName == null || certificationName.isBlank() || certificationName.length() > 160)) {
            throw ApiException.badRequest("Certification name is required");
        }
        if (file == null || file.isEmpty()) throw ApiException.badRequest("File is empty");
        if (file.getSize() > MAX_BYTES) throw ApiException.tooLarge("File is larger than the 5 MB limit");

        UUID profileId = ownProfileId(user.id());
        requireEditable(profileId);
        String originalName = sanitiseName(file.getOriginalFilename());
        FileTypeDetector.Detected detected = FileTypeDetector.detect(readHeader(file), originalName);

        UUID id = UUID.randomUUID();
        String storedName = id + "." + detected.extension();
        Path target = resolveInsideRoot(storedName);
        try {
            Files.createDirectories(root);
            file.transferTo(target);
            db.update("INSERT INTO documents(id, profile_id, type, original_name, stored_name, content_type, size_bytes, sha256, identification_type, certification_name) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, profileId, type, originalName, storedName, detected.contentType(), file.getSize(), sha256(target),
                    type.equals("ID") ? identificationType : null, type.equals("CERTIFICATE") ? certificationName.trim() : null);
            virusScanHook(target);
            audit.record(AuditEvent.of(AuditAction.DOCUMENT_UPLOADED, AuditEntityType.DOCUMENT, id.toString(),
                    Map.of("type", type, "sizeBytes", Long.toString(file.getSize()))));
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(target);
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Document could not be stored");
        }
        return findForProfile(profileId, id);
    }

    public List<DocumentView> listOwn(AuthenticatedUser user) {
        UUID profileId = profileIdOrNull(user.id());
        return profileId == null ? List.of() : listForProfile(profileId);
    }

    public List<DocumentView> listForProfile(UUID profileId) {
        return db.query("SELECT " + COLUMNS + " FROM documents WHERE profile_id = ? AND deleted_at IS NULL ORDER BY uploaded_at",
                VIEW, profileId);
    }

    @Transactional
    public Download downloadOwn(AuthenticatedUser user, UUID documentId) {
        UUID profileId = profileIdOrNull(user.id());
        if (profileId == null) throw ApiException.notFound();
        return download(documentId, profileId);
    }

    @Transactional
    public Download downloadForCase(UUID caseId, UUID documentId) {
        List<UUID> profile = db.queryForList("SELECT profile_id FROM vetting_cases WHERE id = ?", UUID.class, caseId);
        if (profile.isEmpty()) throw ApiException.notFound();
        return download(documentId, profile.get(0));
    }

    @Transactional
    public void deleteOwn(AuthenticatedUser user, UUID documentId) {
        UUID profileId = profileIdOrNull(user.id());
        if (profileId == null) throw ApiException.notFound();
        List<String> stored = db.queryForList(
                "SELECT stored_name FROM documents WHERE id = ? AND profile_id = ? AND deleted_at IS NULL FOR UPDATE",
                String.class, documentId, profileId);
        if (stored.isEmpty()) throw ApiException.notFound();
        requireEditable(profileId);
        db.update("UPDATE documents SET deleted_at = now() WHERE id = ?", documentId);
        audit.record(AuditEvent.of(AuditAction.DOCUMENT_DELETED, AuditEntityType.DOCUMENT, documentId.toString(), null));
        Path file = resolveInsideRoot(stored.get(0));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(file);
            }
        });
    }

    public void purgeForUser(UUID userId) {
        List<String> storedNames = db.queryForList(
                "SELECT d.stored_name FROM documents d JOIN executive_profiles p ON p.id = d.profile_id WHERE p.user_id = ?",
                String.class, userId);
        db.update("DELETE FROM documents WHERE profile_id IN (SELECT id FROM executive_profiles WHERE user_id = ?)", userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storedNames.forEach(name -> deleteQuietly(resolveInsideRoot(name)));
            }
        });
    }

    private Download download(UUID documentId, UUID requiredProfileId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT stored_name, original_name, content_type FROM documents WHERE id = ? AND profile_id = ? AND deleted_at IS NULL",
                documentId, requiredProfileId);
        if (rows.isEmpty()) throw ApiException.notFound();
        Map<String, Object> row = rows.get(0);
        Path path = resolveInsideRoot((String) row.get("stored_name"));
        if (!Files.isRegularFile(path)) throw ApiException.notFound();
        audit.record(AuditEvent.of(AuditAction.DOCUMENT_DOWNLOADED, AuditEntityType.DOCUMENT, documentId.toString(), null));
        return new Download(new FileSystemResource(path), (String) row.get("content_type"), (String) row.get("original_name"));
    }

    private void requireEditable(UUID profileId) {
        List<String> status = db.queryForList("SELECT status FROM vetting_cases WHERE profile_id = ?", String.class, profileId);
        if (!status.isEmpty() && !status.get(0).equals("MORE_INFO_REQUIRED")) {
            throw ApiException.conflict("NOT_EDITABLE", "Documents cannot be changed while your profile is under review");
        }
    }

    private DocumentView findForProfile(UUID profileId, UUID id) {
        return db.queryForObject("SELECT " + COLUMNS + " FROM documents WHERE profile_id = ? AND id = ?", VIEW, profileId, id);
    }

    private UUID ownProfileId(UUID userId) {
        db.update("INSERT INTO executive_profiles(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
        return profileIdOrNull(userId);
    }

    private UUID profileIdOrNull(UUID userId) {
        List<UUID> ids = db.queryForList("SELECT id FROM executive_profiles WHERE user_id = ?", UUID.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Path resolveInsideRoot(String storedName) {
        Path path = root.resolve(storedName).normalize();
        if (!path.getParent().equals(root)) throw new IllegalStateException("Invalid storage path");
        return path;
    }

    // Stub: a real deployment would call an AV scanner here and quarantine on a hit
    private void virusScanHook(Path file) {
    }
}
