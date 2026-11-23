package com.taskhub.config;

import com.taskhub.project.Project;
import com.taskhub.project.ProjectMember;
import com.taskhub.project.ProjectMemberRepository;
import com.taskhub.project.ProjectRepository;
import com.taskhub.project.ProjectRole;
import com.taskhub.project.ProjectStatus;
import com.taskhub.task.Task;
import com.taskhub.task.TaskPriority;
import com.taskhub.task.TaskRepository;
import com.taskhub.task.TaskStatus;
import com.taskhub.user.Role;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent demo data for local development only ({@code dev} profile).
 *
 * <p>Production starts empty by design — no default accounts, no seed passwords. Creating the
 * first administrator is an explicit documented step (see DEPLOYMENT.md).
 */
@Component
@Profile("dev")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final TaskRepository taskRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(UserRepository userRepository, ProjectRepository projectRepository,
                          ProjectMemberRepository memberRepository, TaskRepository taskRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("Demo data already present, skipping seeding");
            return;
        }
        User admin = userRepository.save(new User("admin@taskhub.local",
                passwordEncoder.encode("Admin@12345"), "Ada Admin", Role.ADMIN));
        User manager = userRepository.save(new User("manager@taskhub.local",
                passwordEncoder.encode("Manager@12345"), "Mona Manager", Role.MANAGER));
        User member = userRepository.save(new User("member@taskhub.local",
                passwordEncoder.encode("Member@12345"), "Milo Member", Role.MEMBER));

        Project apollo = new Project("APOLLO", "Apollo Platform Revamp",
                "Consolidate the internal tooling landscape into one platform.", ProjectStatus.ACTIVE, manager);
        Project atlas = new Project("ATLAS", "Atlas Data Migration",
                "Move the reporting warehouse to the new cluster.", ProjectStatus.PLANNED, admin);
        Project zephyr = new Project("ZEPHYR", "Zephyr Mobile Beta",
                "Closed beta of the mobile companion app.", ProjectStatus.ON_HOLD, manager);

        for (Project project : new Project[]{apollo, atlas, zephyr}) {
            project.addMember(new ProjectMember(project, project.getOwner(), ProjectRole.OWNER));
            projectRepository.save(project);
        }
        memberRepository.save(new ProjectMember(apollo, member, ProjectRole.CONTRIBUTOR));
        memberRepository.save(new ProjectMember(apollo, admin, ProjectRole.CONTRIBUTOR));
        memberRepository.save(new ProjectMember(atlas, member, ProjectRole.VIEWER));

        seedTask(apollo, "Design the new task board", "Wireframes and interaction spec for the board.",
                TaskStatus.IN_PROGRESS, TaskPriority.HIGH, LocalDate.now().plusDays(9), member, "16.00");
        seedTask(apollo, "Implement JWT authentication", "Access + refresh token rotation, hashed at rest.",
                TaskStatus.DONE, TaskPriority.CRITICAL, LocalDate.now().minusDays(4), manager, "12.50");
        seedTask(apollo, "OpenAPI documentation pass", "Document every endpoint and error code.",
                TaskStatus.TODO, TaskPriority.MEDIUM, LocalDate.now().plusDays(21), admin, "4.00");
        seedTask(apollo, "Harden rate limiting", "Sliding window on credential endpoints.",
                TaskStatus.BLOCKED, TaskPriority.HIGH, LocalDate.now().minusDays(2), member, "3.00");

        seedTask(atlas, "Inventory legacy schemas", "Catalogue tables, owners and row counts.",
                TaskStatus.DONE, TaskPriority.MEDIUM, LocalDate.now().minusDays(12), member, "8.00");
        seedTask(atlas, "Dry-run migration on staging", "Rehearse cutover and rollback.",
                TaskStatus.TODO, TaskPriority.CRITICAL, LocalDate.now().plusDays(30), admin, "20.00");
        seedTask(atlas, "Write rollback procedure", "Step-by-step rollback with verification gates.",
                TaskStatus.TODO, TaskPriority.HIGH, LocalDate.now().plusDays(14), manager, "6.00");

        seedTask(zephyr, "Recruit beta testers", "Target 200 testers across three regions.",
                TaskStatus.IN_PROGRESS, TaskPriority.LOW, LocalDate.now().plusDays(45), manager, "10.00");
        seedTask(zephyr, "Crash reporting integration", "Symbolicate iOS and Android builds.",
                TaskStatus.TODO, TaskPriority.MEDIUM, LocalDate.now().plusDays(20), member, "5.50");

        log.info("Seeded demo data: admin@taskhub.local / Admin@12345, manager@taskhub.local / Manager@12345, "
                + "member@taskhub.local / Member@12345");
    }

    private void seedTask(Project project, String title, String description, TaskStatus status,
                          TaskPriority priority, LocalDate dueDate, User assignee, String hours) {
        Task task = new Task(project, title, description);
        task.setStatus(status);
        task.setPriority(priority);
        task.setDueDate(dueDate);
        task.setAssignee(assignee);
        task.setEstimatedHours(new BigDecimal(hours));
        taskRepository.save(task);
    }
}
