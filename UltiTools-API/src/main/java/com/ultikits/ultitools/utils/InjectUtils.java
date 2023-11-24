package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.DataCRUD;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Registrable;
import com.ultikits.ultitools.manager.DataProxyManager;
import com.ultikits.ultitools.manager.ServiceProxyManager;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * <h2>依赖注入工具类</h2>
 * 结合延迟加载和动态注入，保证即使所需依赖不存在也能正常注入。
 * <p>
 * 使用代理类进行注入，当依赖被实际需要时，动态获取实际依赖进行调用。
 */
public class InjectUtils {
    public static void injectDataOperator(UltiToolsPlugin plugin, Object object) {
        Field[] fields = object.getClass().getDeclaredFields();

        for (Field field : fields) {
            if (!field.isAnnotationPresent(DataCRUD.class)) {
                continue;
            }
            Type genericType = field.getGenericType();
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Class<? extends AbstractDataEntity> typeArgument = (Class<? extends AbstractDataEntity>) parameterizedType.getActualTypeArguments()[0];
            DataOperator<? extends AbstractDataEntity> dataOperator = new DataProxyManager(typeArgument, plugin).createProxy();
            field.setAccessible(true);
            try {
                field.set(object, dataOperator);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void injectConfigEntity(UltiToolsPlugin plugin, Object object) {
        Field[] fields = object.getClass().getDeclaredFields();

        for (Field field : fields) {
            if (!field.isAnnotationPresent(ConfigEntity.class)) {
                continue;
            }
            Class<?> fieldType = field.getType();
            String path = field.getAnnotation(ConfigEntity.class).value();
            AbstractConfigEntity configEntity = plugin.getConfig(path, (Class<? extends AbstractConfigEntity>) fieldType);
            field.setAccessible(true);
            try {
                field.set(object, configEntity);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void injectService(Object object) {
        Field[] fields = object.getClass().getDeclaredFields();

        for (Field field : fields) {
            if (!field.isAnnotationPresent(Autowired.class)) {
                continue;
            }
            Class<?> fieldType = field.getType();
            ServiceProxyManager serviceProxyManager = new ServiceProxyManager((Class<? extends Registrable>) fieldType);
            Registrable registrable = serviceProxyManager.createProxy();
            field.setAccessible(true);
            try {
                field.set(object, registrable);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
