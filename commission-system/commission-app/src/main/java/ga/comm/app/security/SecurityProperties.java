package ga.comm.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 인증 사용자 — 외부 구성에서만 온다(저장소에 비밀 0). {@code app.security.users[*]}로 주입하며,
 * 비어 있으면 인증 가능한 사용자가 없다(= 인증 없는 배포 금지: 사용자를 구성하지 않으면 아무도
 * 통과하지 못한다). 사내 SSO 연동 시 이 인메모리 사용자 대신 SSO UserDetailsService로 교체한다(후속).
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private List<User> users = new ArrayList<>();

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    /** password는 {noop}/{bcrypt} 접두 인코딩(운영은 {bcrypt} 해시, 저장소엔 평문 비밀 없음). */
    public static class User {
        private String username;
        private String password;
        private String role; // ADMIN / OPERATOR / VIEWER

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
