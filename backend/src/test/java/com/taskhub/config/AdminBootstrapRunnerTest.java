package com.taskhub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.taskhub.user.Role;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private AdminBootstrapRunner runner(String email, String password) {
        return new AdminBootstrapRunner(userRepository, encoder, email, password, "Root Admin");
    }

    @Test
    void createsTheFirstAdministrator() throws Exception {
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(0L);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        runner("root@example.com", "Adm1nPassword!").run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("root@example.com");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getPasswordHash()).isNotEqualTo("Adm1nPassword!");
        assertThat(encoder.matches("Adm1nPassword!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() throws Exception {
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(1L);

        runner("root@example.com", "Adm1nPassword!").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesWeakPasswords() throws Exception {
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(0L);

        runner("root@example.com", "short").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void isDisabledWhenNoEmailIsConfigured() throws Exception {
        runner("", "Adm1nPassword!").run(null);

        verify(userRepository, never()).save(any());
    }
}
