package com.ultikits.ultitools.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Bean definition to hold metadata about a bean.
 */
@ApiStatus.Internal
public class BeanDefinition {
    private Class<?> beanClass;
    private String beanName;
    private SimpleContainer.BeanScope scope;
    private Object instance;
    private Method factoryMethod;
    private Object factoryBean;
    private List<String> dependsOn;
    private boolean lazyInit;
    private Object[] constructorArgValues;

    public BeanDefinition() {
        this.scope = SimpleContainer.BeanScope.SINGLETON;
        this.dependsOn = new ArrayList<>();
        this.lazyInit = false;
    }

    public BeanDefinition(Class<?> beanClass) {
        this();
        this.beanClass = beanClass;
    }

    public BeanDefinition(Class<?> beanClass, String beanName) {
        this();
        this.beanClass = beanClass;
        this.beanName = beanName;
    }

    public Class<?> getBeanClass() {
        return beanClass;
    }

    public void setBeanClass(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    public String getBeanName() {
        return beanName;
    }

    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    public SimpleContainer.BeanScope getScope() {
        return scope;
    }

    public void setScope(SimpleContainer.BeanScope scope) {
        this.scope = scope;
    }

    public Object getInstance() {
        return instance;
    }

    public void setInstance(Object instance) {
        this.instance = instance;
    }

    public Method getFactoryMethod() {
        return factoryMethod;
    }

    public void setFactoryMethod(Method factoryMethod) {
        this.factoryMethod = factoryMethod;
    }

    public Object getFactoryBean() {
        return factoryBean;
    }

    public void setFactoryBean(Object factoryBean) {
        this.factoryBean = factoryBean;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }

    public boolean isLazyInit() {
        return lazyInit;
    }

    public void setLazyInit(boolean lazyInit) {
        this.lazyInit = lazyInit;
    }

    public boolean isSingleton() {
        return scope == SimpleContainer.BeanScope.SINGLETON;
    }

    public boolean isPrototype() {
        return scope == SimpleContainer.BeanScope.PROTOTYPE;
    }

    public Object[] getConstructorArgValues() {
        return constructorArgValues;
    }

    public void setConstructorArgValues(Object[] constructorArgValues) {
        this.constructorArgValues = constructorArgValues;
    }
}
