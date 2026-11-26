package com.fluxpay.gateway;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Records the SQL the application sends to the database, so a test can state what one request
 * costs in statements instead of guessing. It wraps the application's {@link DataSource} in a
 * JDK proxy: every {@code prepareStatement(sql)} and every {@code Statement.execute*(sql)} is
 * noted while recording is on. JPA and JdbcClient both go through it.
 *
 * <p>A prepared statement is counted when it is prepared; on the paths measured here each one
 * is executed exactly once. Import {@link Config} only in the test class that needs it: doing
 * so gives that class its own application context.
 */
public class StatementCounter implements BeanPostProcessor {

    private final List<String> statements = new CopyOnWriteArrayList<>();
    private volatile boolean recording;

    public void start() {
        statements.clear();
        recording = true;
    }

    /** Stops recording and returns the SQL seen since {@link #start()}, in order. */
    public List<String> stop() {
        recording = false;
        return List.copyOf(statements);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource) {
            return proxy(DataSource.class, dataSource, (method, args) -> {
                Object result = method.invoke(dataSource, args);
                return result instanceof Connection connection ? wrap(connection) : result;
            });
        }
        return bean;
    }

    private Connection wrap(Connection connection) {
        return proxy(Connection.class, connection, (method, args) -> {
            Object result = method.invoke(connection, args);
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall")) && args != null) {
                note((String) args[0]);
            } else if (name.equals("createStatement")) {
                return wrap((Statement) result);
            }
            return result;
        });
    }

    private Statement wrap(Statement statement) {
        return proxy(Statement.class, statement, (method, args) -> {
            if (method.getName().startsWith("execute") && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                note(sql);
            }
            return method.invoke(statement, args);
        });
    }

    private void note(String sql) {
        if (recording) {
            statements.add(sql.strip().replaceAll("\\s+", " "));
        }
    }

    private interface Call {
        Object apply(java.lang.reflect.Method method, Object[] args) throws ReflectiveOperationException;
    }

    private static <T> T proxy(Class<T> type, T target, Call call) {
        InvocationHandler handler = (self, method, args) -> {
            try {
                return call.apply(method, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause(); // surface the driver's own exception, as without the proxy
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        public static StatementCounter statementCounter() {
            return new StatementCounter();
        }
    }
}
