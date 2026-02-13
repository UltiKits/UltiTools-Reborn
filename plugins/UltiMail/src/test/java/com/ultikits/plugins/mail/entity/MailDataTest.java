package com.ultikits.plugins.mail.entity;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MailData entity.
 * <p>
 * 测试邮件数据实体的各项功能。
 */
@DisplayName("MailData 实体测试")
class MailDataTest {

    private MailData mailData;

    @BeforeEach
    void setUp() {
        mailData = new MailData();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("默认构造函数应该初始化所有布尔字段为 false")
        void shouldInitializeBooleanFieldsToFalse() {
            MailData mail = new MailData();

            assertThat(mail.isRead()).isFalse();
            assertThat(mail.isClaimed()).isFalse();
            assertThat(mail.isCommandsExecuted()).isFalse();
            assertThat(mail.isDeletedBySender()).isFalse();
            assertThat(mail.isDeletedByReceiver()).isFalse();
        }

        @Test
        @DisplayName("默认构造函数应该设置发送时间")
        void shouldSetSentTime() {
            long before = System.currentTimeMillis();
            MailData mail = new MailData();
            long after = System.currentTimeMillis();

            assertThat(mail.getSentTime()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("hasItems() 方法测试")
    class HasItemsTests {

        @Test
        @DisplayName("items 为 null 时应返回 false")
        void shouldReturnFalseWhenItemsIsNull() {
            mailData.setItems(null);
            
            assertThat(mailData.hasItems()).isFalse();
        }

        @Test
        @DisplayName("items 为空字符串时应返回 false")
        void shouldReturnFalseWhenItemsIsEmpty() {
            mailData.setItems("");
            
            assertThat(mailData.hasItems()).isFalse();
        }

        @Test
        @DisplayName("items 有内容时应返回 true")
        void shouldReturnTrueWhenItemsHasContent() {
            mailData.setItems("rO0ABXcEAAAAAXNyABxvcmcuYnVra2l0Lmludm..."); // Base64 encoded
            
            assertThat(mailData.hasItems()).isTrue();
        }
    }

    @Nested
    @DisplayName("hasCommands() 方法测试")
    class HasCommandsTests {

        @Test
        @DisplayName("commands 为 null 时应返回 false")
        void shouldReturnFalseWhenCommandsIsNull() {
            mailData.setCommands(null);
            
            assertThat(mailData.hasCommands()).isFalse();
        }

        @Test
        @DisplayName("commands 为空字符串时应返回 false")
        void shouldReturnFalseWhenCommandsIsEmpty() {
            mailData.setCommands("");
            
            assertThat(mailData.hasCommands()).isFalse();
        }

        @Test
        @DisplayName("commands 为空数组 [] 时应返回 false")
        void shouldReturnFalseWhenCommandsIsEmptyArray() {
            mailData.setCommands("[]");
            
            assertThat(mailData.hasCommands()).isFalse();
        }

        @Test
        @DisplayName("commands 有内容时应返回 true")
        void shouldReturnTrueWhenCommandsHasContent() {
            mailData.setCommands("[\"give %player% diamond 1\"]");
            
            assertThat(mailData.hasCommands()).isTrue();
        }

        @Test
        @DisplayName("commands 有多个命令时应返回 true")
        void shouldReturnTrueWithMultipleCommands() {
            mailData.setCommands("[\"give %player% diamond 1\",\"console:eco give %player% 100\"]");
            
            assertThat(mailData.hasCommands()).isTrue();
        }
    }

    @Nested
    @DisplayName("Getter/Setter 测试")
    class GetterSetterTests {

        @Test
        @DisplayName("应该正确设置和获取发送者信息")
        void shouldSetAndGetSenderInfo() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            String name = "TestSender";

            mailData.setSenderUuid(uuid);
            mailData.setSenderName(name);

            assertThat(mailData.getSenderUuid()).isEqualTo(uuid);
            assertThat(mailData.getSenderName()).isEqualTo(name);
        }

        @Test
        @DisplayName("应该正确设置和获取接收者信息")
        void shouldSetAndGetReceiverInfo() {
            String uuid = "660e8400-e29b-41d4-a716-446655440001";
            String name = "TestReceiver";

            mailData.setReceiverUuid(uuid);
            mailData.setReceiverName(name);

            assertThat(mailData.getReceiverUuid()).isEqualTo(uuid);
            assertThat(mailData.getReceiverName()).isEqualTo(name);
        }

        @Test
        @DisplayName("应该正确设置和获取邮件内容")
        void shouldSetAndGetMailContent() {
            String subject = "测试标题";
            String content = "这是邮件内容";

            mailData.setSubject(subject);
            mailData.setContent(content);

            assertThat(mailData.getSubject()).isEqualTo(subject);
            assertThat(mailData.getContent()).isEqualTo(content);
        }

        @Test
        @DisplayName("应该正确设置和获取状态标志")
        void shouldSetAndGetStatusFlags() {
            mailData.setRead(true);
            mailData.setClaimed(true);
            mailData.setCommandsExecuted(true);
            mailData.setDeletedBySender(true);
            mailData.setDeletedByReceiver(true);

            assertThat(mailData.isRead()).isTrue();
            assertThat(mailData.isClaimed()).isTrue();
            assertThat(mailData.isCommandsExecuted()).isTrue();
            assertThat(mailData.isDeletedBySender()).isTrue();
            assertThat(mailData.isDeletedByReceiver()).isTrue();
        }
    }

    @Nested
    @DisplayName("equals 和 hashCode 测试")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("相同数据的两个对象应该相等")
        void shouldBeEqualWithSameData() {
            MailData mail1 = createTestMail();
            MailData mail2 = createTestMail();

            // Note: BaseDataEntity uses ID for equals
            mail1.setId("1");
            mail2.setId("1");

            assertThat(mail1).isEqualTo(mail2);
            assertThat(mail1.hashCode()).isEqualTo(mail2.hashCode());
        }

        @Test
        @DisplayName("不同ID的两个对象不应该相等")
        void shouldNotBeEqualWithDifferentId() {
            MailData mail1 = createTestMail();
            MailData mail2 = createTestMail();

            mail1.setId("1");
            mail2.setId("2");

            assertThat(mail1).isNotEqualTo(mail2);
        }

        private MailData createTestMail() {
            MailData mail = new MailData();
            mail.setSenderUuid("uuid1");
            mail.setSenderName("sender");
            mail.setReceiverUuid("uuid2");
            mail.setReceiverName("receiver");
            mail.setSubject("subject");
            mail.setContent("content");
            return mail;
        }
    }
}
