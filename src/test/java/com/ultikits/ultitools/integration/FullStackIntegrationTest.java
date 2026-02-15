package com.ultikits.ultitools.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.context.BeanDefinition;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * Full stack integration tests for the IoC container and related components.
 * Tests real-world scenarios with multiple interacting components.
 */
@DisplayName("Full Stack Integration Tests")
class FullStackIntegrationTest {

    private SimpleContainer container;

    // ==================== Test Components ====================

    @Service
    public static class UserService {
        @Autowired
        private UserRepository userRepository;
        
        @Autowired
        private NotificationService notificationService;
        
        private boolean initialized = false;
        
        public void initialize() {
            this.initialized = true;
        }
        
        public String createUser(String name) {
            String userId = userRepository.save(name);
            notificationService.sendWelcome(name);
            return userId;
        }
        
        public UserRepository getUserRepository() {
            return userRepository;
        }
        
        public NotificationService getNotificationService() {
            return notificationService;
        }
        
        public boolean isInitialized() {
            return initialized;
        }
    }

    @Component
    public static class UserRepository {
        private int saveCount = 0;
        
        public String save(String name) {
            saveCount++;
            return "user-" + saveCount;
        }
        
        public int getSaveCount() {
            return saveCount;
        }
    }

    @Service
    public static class NotificationService {
        @Autowired
        private EmailService emailService;
        
        private int notifyCount = 0;
        
        public void sendWelcome(String name) {
            notifyCount++;
            emailService.send(name + "@example.com", "Welcome!");
        }
        
        public int getNotifyCount() {
            return notifyCount;
        }
        
        public EmailService getEmailService() {
            return emailService;
        }
    }

    @Component
    public static class EmailService {
        private int sendCount = 0;
        
        public void send(String to, String message) {
            sendCount++;
        }
        
        public int getSendCount() {
            return sendCount;
        }
    }

    // Circular dependency test components
    @Service
    public static class ServiceA {
        @Autowired
        private ServiceB serviceB;
        
        public ServiceB getServiceB() {
            return serviceB;
        }
    }

    @Service
    public static class ServiceB {
        @Autowired
        private ServiceA serviceA;
        
        public ServiceA getServiceA() {
            return serviceA;
        }
    }

    // Multiple levels of dependencies
    @Service
    public static class Level1Service {
        @Autowired
        private Level2Service level2;
        
        public Level2Service getLevel2() {
            return level2;
        }
    }

    @Service
    public static class Level2Service {
        @Autowired
        private Level3Service level3;
        
        public Level3Service getLevel3() {
            return level3;
        }
    }

    @Service
    public static class Level3Service {
        @Autowired
        private Level4Service level4;
        
        public Level4Service getLevel4() {
            return level4;
        }
    }

    @Component
    public static class Level4Service {
        private final String name = "Level4";
        
        public String getName() {
            return name;
        }
    }

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
    }

    @AfterEach
    void tearDown() {
        container.close();
    }

    @Nested
    @DisplayName("Multi-Layer Dependency Injection Tests")
    class MultiLayerDependencyTests {

        @Test
        @DisplayName("should inject dependencies through multiple layers")
        void shouldInjectThroughMultipleLayers() {
            // Register all services
            container.registerBeanDefinition("userService", new BeanDefinition(UserService.class));
            container.registerBeanDefinition("userRepository", new BeanDefinition(UserRepository.class));
            container.registerBeanDefinition("notificationService", new BeanDefinition(NotificationService.class));
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            // Get top-level service
            UserService userService = (UserService) container.getBean("userService");
            
            // Verify all dependencies are injected
            assertNotNull(userService);
            assertNotNull(userService.getUserRepository());
            assertNotNull(userService.getNotificationService());
            assertNotNull(userService.getNotificationService().getEmailService());
        }

        @Test
        @DisplayName("should work correctly with 4 levels of dependencies")
        void shouldHandleFourLevels() {
            // Use type-based names that AutowireFactory can find
            container.registerBeanDefinition("level1Service", new BeanDefinition(Level1Service.class));
            container.registerBeanDefinition("level2Service", new BeanDefinition(Level2Service.class));
            container.registerBeanDefinition("level3Service", new BeanDefinition(Level3Service.class));
            container.registerBeanDefinition("level4Service", new BeanDefinition(Level4Service.class));
            
            Level1Service level1 = (Level1Service) container.getBean("level1Service");
            
            assertNotNull(level1, "Level1 should not be null");
            assertNotNull(level1.getLevel2(), "Level2 should be injected");
            assertNotNull(level1.getLevel2().getLevel3(), "Level3 should be injected");
            assertNotNull(level1.getLevel2().getLevel3().getLevel4(), "Level4 should be injected");
            assertEquals("Level4", level1.getLevel2().getLevel3().getLevel4().getName());
        }
    }

    @Nested
    @DisplayName("Circular Dependency Resolution Tests")
    class CircularDependencyTests {

        @Test
        @DisplayName("should resolve two-way circular dependencies")
        void shouldResolveTwoWayCircular() {
            container.registerBeanDefinition("serviceA", new BeanDefinition(ServiceA.class));
            container.registerBeanDefinition("serviceB", new BeanDefinition(ServiceB.class));
            
            ServiceA serviceA = (ServiceA) container.getBean("serviceA");
            ServiceB serviceB = (ServiceB) container.getBean("serviceB");
            
            assertNotNull(serviceA);
            assertNotNull(serviceB);
            assertSame(serviceB, serviceA.getServiceB());
            assertSame(serviceA, serviceB.getServiceA());
        }
    }

    @Nested
    @DisplayName("Singleton Behavior Tests")
    class SingletonBehaviorTests {

        @Test
        @DisplayName("should return same instance for singleton beans")
        void shouldReturnSameInstance() {
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            EmailService instance1 = (EmailService) container.getBean("emailService");
            EmailService instance2 = (EmailService) container.getBean("emailService");
            
            assertSame(instance1, instance2);
        }

        @Test
        @DisplayName("should share singleton across dependencies")
        void shouldShareSingletonAcrossDependencies() {
            container.registerBeanDefinition("userService", new BeanDefinition(UserService.class));
            container.registerBeanDefinition("userRepository", new BeanDefinition(UserRepository.class));
            container.registerBeanDefinition("notificationService", new BeanDefinition(NotificationService.class));
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            UserService userService = (UserService) container.getBean("userService");
            NotificationService notificationService = (NotificationService) container.getBean("notificationService");
            
            // Both should reference the same EmailService
            assertSame(
                userService.getNotificationService().getEmailService(),
                notificationService.getEmailService()
            );
        }
    }

    @Nested
    @DisplayName("Functional Integration Tests")
    class FunctionalTests {

        @Test
        @DisplayName("should complete user creation workflow")
        void shouldCompleteUserCreationWorkflow() {
            // Setup
            container.registerBeanDefinition("userService", new BeanDefinition(UserService.class));
            container.registerBeanDefinition("userRepository", new BeanDefinition(UserRepository.class));
            container.registerBeanDefinition("notificationService", new BeanDefinition(NotificationService.class));
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            UserService userService = (UserService) container.getBean("userService");
            
            // Execute
            String userId = userService.createUser("John");
            
            // Verify
            assertEquals("user-1", userId);
            assertEquals(1, userService.getUserRepository().getSaveCount());
            assertEquals(1, userService.getNotificationService().getNotifyCount());
            assertEquals(1, userService.getNotificationService().getEmailService().getSendCount());
        }

        @Test
        @DisplayName("should track state across multiple operations")
        void shouldTrackStateAcrossOperations() {
            container.registerBeanDefinition("userService", new BeanDefinition(UserService.class));
            container.registerBeanDefinition("userRepository", new BeanDefinition(UserRepository.class));
            container.registerBeanDefinition("notificationService", new BeanDefinition(NotificationService.class));
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            UserService userService = (UserService) container.getBean("userService");
            
            // Create multiple users
            userService.createUser("User1");
            userService.createUser("User2");
            userService.createUser("User3");
            
            // Verify accumulated state
            assertEquals(3, userService.getUserRepository().getSaveCount());
            assertEquals(3, userService.getNotificationService().getNotifyCount());
            assertEquals(3, userService.getNotificationService().getEmailService().getSendCount());
        }
    }

    @Nested
    @DisplayName("Parent Container Tests")
    class ParentContainerTests {

        @Test
        @DisplayName("should inherit beans from parent container")
        void shouldInheritFromParent() {
            // Parent container with shared services
            SimpleContainer parentContainer = new SimpleContainer();
            parentContainer.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            // Child container
            container.setParent(parentContainer);
            container.registerBeanDefinition("notificationService", new BeanDefinition(NotificationService.class));
            
            // NotificationService should get EmailService from parent
            NotificationService notificationService = (NotificationService) container.getBean("notificationService");
            
            assertNotNull(notificationService);
            assertNotNull(notificationService.getEmailService());
            
            parentContainer.close();
        }
    }

    @Nested
    @DisplayName("Container Lifecycle Tests")
    class ContainerLifecycleTests {

        @Test
        @DisplayName("should clear beans on close")
        void shouldClearBeansOnClose() {
            container.registerBeanDefinition("emailService", new BeanDefinition(EmailService.class));
            
            // Bean exists before close
            assertNotNull(container.getBean("emailService"));
            
            // Close container
            container.close();
            
            // Bean should not exist after close
            assertNull(container.getBean("emailService"));
        }
    }
}
