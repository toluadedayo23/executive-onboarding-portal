package ng.risktech.execplatform.user;

import ng.risktech.execplatform.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import ng.risktech.execplatform.onboarding.FieldEncryptionService;

@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "app.seed-enabled", havingValue = "true")
public class DevSeed {
    DevSeed(UserRepository r, AuthService a, JdbcTemplate db, FieldEncryptionService crypto, ObjectMapper json, @Value("${SEED_PASSWORD:}") String p) {
        if (p.isBlank()) throw new IllegalStateException("SEED_PASSWORD is required when seeding is enabled");
        String[][] x = {{"admin@example.test", "ADMIN"}, {"consultant1@example.test", "CONSULTANT"}, {"consultant2@example.test", "CONSULTANT"}, {"exec1@example.test", "EXECUTIVE"}, {"exec2@example.test", "EXECUTIVE"}, {"exec3@example.test", "EXECUTIVE"}, {"exec4@example.test", "EXECUTIVE"}};
        for (String[] row : x) if (!r.existsByEmailIgnoreCase(row[0])) a.create(row[0], p, row[1]);
        seedDraft(r, db, crypto, json, "exec2@example.test", "Chidi Synthetic", 3, false);
        seedDraft(r, db, crypto, json, "exec3@example.test", "Folake Synthetic", 5, true);
    }

    private void seedDraft(UserRepository users, JdbcTemplate db, FieldEncryptionService crypto, ObjectMapper json, String email, String fullName, int step, boolean complete) {
        users.findByEmailIgnoreCase(email).ifPresent(u -> {
            if (db.queryForObject("SELECT count(*) FROM executive_profiles WHERE user_id=?", Integer.class, u.getId()) > 0) return;
            try {
                var root = json.createObjectNode();
                root.putObject("profile").put("fullName", fullName).put("headline", "Governance Leader").put("summary", "A synthetic demonstration profile used only to show the online onboarding flow and its validation states.").put("phone", "+234 800 000 0000").put("country", "Nigeria").put("city", "Lagos").put("nationalId", crypto.encrypt("SYNTHETIC1234", "profile.nationalId"));
                var experience = root.putObject("experience").putArray("items"); experience.addObject().put("type", "EXECUTIVE").put("organisation", "Example Governance Ltd").put("title", "Chief Risk Officer").put("startDate", "2020-01-01").putNull("endDate").put("description", "Synthetic experience leading enterprise risk and governance.");
                var sectors = root.putObject("sectors").putArray("items"); sectors.addObject().put("sector", "Technology").put("yearsExperience", 8);
                var qualifications = root.putObject("qualifications").putArray("items"); qualifications.addObject().put("title", "MBA").put("institution", "Example University").put("yearObtained", 2018);
                var refs = root.putObject("referees").putArray("items"); refs.addObject().put("id", "00000000-0000-4000-8000-000000000001").put("name", "Synthetic Referee One").put("relationship", "Former colleague").put("email", crypto.encrypt("referee1@example.test", "referee.email")).put("phone", crypto.encrypt("+234 800 000 0001", "referee.phone"));
                if (complete) refs.addObject().put("id", "00000000-0000-4000-8000-000000000002").put("name", "Synthetic Referee Two").put("relationship", "Board peer").put("email", crypto.encrypt("referee2@example.test", "referee.email")).put("phone", crypto.encrypt("+234 800 000 0002", "referee.phone"));
                db.update("INSERT INTO executive_profiles(user_id,draft_data,current_step) VALUES(?,CAST(? AS jsonb),?)",u.getId(),json.writeValueAsString(root),step);
            } catch (Exception ex) { throw new IllegalStateException("Unable to create synthetic demo profile", ex); }
        });
    }
}
