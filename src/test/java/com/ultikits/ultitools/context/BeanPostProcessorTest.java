package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BeanPostProcessor interface.
 * <br>
 * BeanPostProcessor接口的单元测试。
 */
@DisplayName("BeanPostProcessor Tests")
class BeanPostProcessorTest {

    private BeanPostProcessor postProcessor;
    private TestBean testBean;
    private String beanName;

    @BeforeEach
    void setUp() {
        postProcessor = new TestBeanPostProcessor();
        testBean = new TestBean("original");
        beanName = "testBean";
    }

    @Test
    @DisplayName("Should execute postProcessBeforeInitialization")
    void testPostProcessBeforeInitialization() {
        // When
        Object result = postProcessor.postProcessBeforeInitialization(testBean, beanName);

        // Then
        assertNotNull(result);
        assertInstanceOf(TestBean.class, result);
        TestBean processedBean = (TestBean) result;
        assertEquals("original_before", processedBean.getValue());
    }

    @Test
    @DisplayName("Should execute postProcessAfterInitialization")
    void testPostProcessAfterInitialization() {
        // When
        Object result = postProcessor.postProcessAfterInitialization(testBean, beanName);

        // Then
        assertNotNull(result);
        assertInstanceOf(TestBean.class, result);
        TestBean processedBean = (TestBean) result;
        assertEquals("original_after", processedBean.getValue());
    }

    @Test
    @DisplayName("Should chain both post processors")
    void testChainedPostProcessors() {
        // When
        Object intermediate = postProcessor.postProcessBeforeInitialization(testBean, beanName);
        Object result = postProcessor.postProcessAfterInitialization(intermediate, beanName);

        // Then
        assertNotNull(result);
        TestBean processedBean = (TestBean) result;
        assertEquals("original_before_after", processedBean.getValue());
    }

    @Test
    @DisplayName("Should handle null bean name gracefully")
    void testHandleNullBeanName() {
        // When
        Object resultBefore = postProcessor.postProcessBeforeInitialization(testBean, null);
        Object resultAfter = postProcessor.postProcessAfterInitialization(testBean, null);

        // Then
        assertNotNull(resultBefore);
        assertNotNull(resultAfter);
    }

    @Test
    @DisplayName("Default implementation should return original bean")
    void testDefaultImplementation() {
        // Given
        BeanPostProcessor defaultProcessor = new BeanPostProcessor() {
            // Using default methods only
        };

        // When
        Object resultBefore = defaultProcessor.postProcessBeforeInitialization(testBean, beanName);
        Object resultAfter = defaultProcessor.postProcessAfterInitialization(testBean, beanName);

        // Then
        assertSame(testBean, resultBefore);
        assertSame(testBean, resultAfter);
    }

    @Test
    @DisplayName("Should work with different bean types")
    void testWithDifferentBeanTypes() {
        // Given
        String stringBean = "testString";
        Integer intBean = 42;

        // When
        Object processedString = postProcessor.postProcessBeforeInitialization(stringBean, "stringBean");
        Object processedInt = postProcessor.postProcessBeforeInitialization(intBean, "intBean");

        // Then
        assertNotNull(processedString);
        assertNotNull(processedInt);
    }

    @Test
    @DisplayName("Should preserve bean identity if implementation returns same bean")
    void testBeanIdentityPreservation() {
        // Given
        BeanPostProcessor identityProcessor = new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                return bean; // Return same bean
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean; // Return same bean
            }
        };

        // When
        Object resultBefore = identityProcessor.postProcessBeforeInitialization(testBean, beanName);
        Object resultAfter = identityProcessor.postProcessAfterInitialization(testBean, beanName);

        // Then
        assertSame(testBean, resultBefore);
        assertSame(testBean, resultAfter);
    }

    @Test
    @DisplayName("Should handle multiple sequential processing")
    void testMultipleSequentialProcessing() {
        // Given
        BeanPostProcessor processor1 = new TestBeanPostProcessor();
        BeanPostProcessor processor2 = new TestBeanPostProcessor();

        // When
        Object step1 = processor1.postProcessBeforeInitialization(testBean, beanName);
        Object step2 = processor2.postProcessBeforeInitialization(step1, beanName);
        Object step3 = processor1.postProcessAfterInitialization(step2, beanName);
        Object result = processor2.postProcessAfterInitialization(step3, beanName);

        // Then
        assertNotNull(result);
        TestBean finalBean = (TestBean) result;
        assertEquals("original_before_before_after_after", finalBean.getValue());
    }

    // Test helper classes
    private static class TestBean {
        private String value;

        public TestBean(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private static class TestBeanPostProcessor implements BeanPostProcessor {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof TestBean) {
                TestBean testBean = (TestBean) bean;
                testBean.setValue(testBean.getValue() + "_before");
            }
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof TestBean) {
                TestBean testBean = (TestBean) bean;
                testBean.setValue(testBean.getValue() + "_after");
            }
            return bean;
        }
    }
}
