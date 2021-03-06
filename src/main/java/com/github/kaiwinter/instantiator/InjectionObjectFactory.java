package com.github.kaiwinter.instantiator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kaiwinter.instantiator.LookupContext.PackageScope;

/**
 * Object Factory which creates fully initialized instances by means of trying to set member variables which are else
 * injected by an application container.
 */
public final class InjectionObjectFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(InjectionObjectFactory.class);

    /**
     * If not specified by the user {@link Inject} annotations will be processed.
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Annotation>[] DEFAULT_ANNOTATIONS_TO_PROCESS = new Class[] { Inject.class };

    /** Cached instances for classes (or interfaces). */
    private Map<Class<?>, Object> classOrInterface2Instance = new HashMap<>();

    /** Cached Reflections instances for Packages. (Are used to find implementations of interfaces. */
    private Map<Package, Reflections> package2Reflection = new HashMap<>();

    /** Cached Types with missing implementations to faster skip them. */
    private Set<Type> missingImplementations = new HashSet<>();

    /** Implementations for <b>interfaces</b> which were set by the user. */
    private Map<Class<?>, Class<?>> userSetInterface2Class = new HashMap<>();

    /** Fields annotated with these annotations will be set by the factory. */
    private Set<Class<? extends Annotation>> annotationsToProcess;
    
    private final LookupContext lookupContext;

    /**
     * Constructs a new {@link InjectionObjectFactory} which will inject beans which are annotated with the given
     * <code>annotationsToProcess</code>. The whole classpath is searched for implementations.
     *
     * @param annotationsToProcess
     *            the annotations which should be considered as "to be injected", if not set {@link #DEFAULT_ANNOTATIONS_TO_PROCESS} will be
     *            used.
     */
    @SafeVarargs
    public InjectionObjectFactory(Class<? extends Annotation>... annotationsToProcess) {
        this(new LookupContext(PackageScope.WHOLE_CLASSPATH), annotationsToProcess);
    }
    
    /**
     * Constructs a new {@link InjectionObjectFactory} which will inject beans which are annotated with the given
     * <code>annotationsToProcess</code>.
     *S
     * @param lookupContext
     *            defines in which scope types are looked up
     * @param annotationsToProcess
     *            the annotations which should be considered as "to be injected", if not set {@link #DEFAULT_ANNOTATIONS_TO_PROCESS} will be
     *            used.
     * 
     * @see LookupContext
     */
    @SafeVarargs
    public InjectionObjectFactory(LookupContext lookupContext, Class<? extends Annotation>... annotationsToProcess) {
        Objects.requireNonNull(lookupContext, "No lookup context set");
        
        this.lookupContext = lookupContext;
        if (annotationsToProcess.length == 0) {
            this.annotationsToProcess = new HashSet<>(Arrays.asList(DEFAULT_ANNOTATIONS_TO_PROCESS));
        } else {
            this.annotationsToProcess = new HashSet<>(Arrays.asList(annotationsToProcess));
        }
    }

    /**
     * Returns an fully initialized instance of the given <code>clazz</code>, instances are cached and re-used.
     *
     * @param clazz
     *            the {@link Class} to get an instance of
     * @return fully initialized instance
     * @throws IllegalArgumentException
     *             if more than one implementation for an interface was found
     */
    public <T> T getInstance(Class<T> clazz) throws IllegalArgumentException {
        LOGGER.trace("Processing: {}", clazz);
        if (clazz == null) {
            return null;
        } else if (clazz.isInterface()) {
            throw new IllegalArgumentException("A class must be passed");
        }

        @SuppressWarnings("unchecked")
        T instance = (T) classOrInterface2Instance.get(clazz);
        if (instance != null) {
            return instance;
        }

        try {
            instance = clazz.newInstance();
            classOrInterface2Instance.put(clazz, instance);
        } catch (NoClassDefFoundError | IllegalAccessException | InstantiationException e) {
            LOGGER.error("Could not instantiate class {}", clazz, e);
            // TODO KW: Automatically create mock?
            return null;
        }

        // Iterate and set annotated fields
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field field : declaredFields) {
            Annotation[] annotations = field.getDeclaredAnnotations();
            for (Annotation annotation : annotations) {
                if (annotationsToProcess.contains(annotation.annotationType())) {
                    LOGGER.trace("Trying to set '{}' of type: {}", field.getName(), field.getType());
                    setFieldInInstance(instance, field);
                }
            }
        }

        return instance;
    }

    /**
     * Sets the <code>field</code> in the given instance.
     * <ul>
     * <li>If the user has set an instance by {@link #setImplementationForClassOrInterface(Class, Object)} this one is set</li>
     * <li>If the field is an interface type the implementation is looked up. If there is more than one implementation an
     * IllegalArgumentException is thrown. Use {@link #setImplementationForClassOrInterface(Class, Object)} to denote one.</li>
     * <li>If field is an class it is used directly.</li> <br/>
     * The found implementation isn't instantiated directly but gets created by {@link #getInstance(Class)} (recursion).
     * </ul>
     *
     * @param instance
     *            the object instance containing the field
     * @param field
     *            the Field to set
     * @throws IllegalArgumentException
     *             if more than one implementation was found
     */
    private void setFieldInInstance(Object instance, Field field) throws IllegalArgumentException {
        Object instanceToSet = getInstanceToSet(field);

        if (instanceToSet != null) {
            field.setAccessible(true);
            try {
                field.set(instance, instanceToSet);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                LOGGER.error("Could not set field {}: ", field.getName(), e);
            }
        }
    }

    /**
     * Looks up an instance for the given <code>field</code>.
     * <ul>
     * <li>If the user has set an instance by {@link #setImplementationForClassOrInterface(Class, Object)} this one is set</li>
     * <li>If the field is an interface type the implementation is looked up. If there is more than one implementation an
     * IllegalArgumentException is thrown. Use {@link #setImplementationForClassOrInterface(Class, Object)} to denote one.</li>
     * <li>If field is an class it is used directly.</li> <br/>
     * The found implementation isn't instantiated directly but gets created by {@link #getInstance(Class)} (recursion).
     * </ul>
     * 
     * @param field
     *            the Field to set
     * @return an instance which can be assigned to the field
     * @throws IllegalArgumentException
     *             if more than one implementation was found
     */
    private Object getInstanceToSet(Field field) throws IllegalArgumentException {
        if (classOrInterface2Instance.containsKey(field.getType())) {
            // Re-use from cache
            return classOrInterface2Instance.get(field.getType());
        }

        Class<?> implementation;
        if (field.getType().isInterface()) {
            implementation = getImplementationForInterface(field);
        } else {
            // Field is a class
            LOGGER.trace("Using class of type as it is an implementing class");
            implementation = field.getType();
        }

        if (implementation == null) {
            LOGGER.warn("No implementation found for {}", field.getType());
            return null;
        }

        Object objectInInstance = getInstance(implementation);
        return objectInInstance;
    }

    /**
     * @throws IllegalArgumentException
     *             if more than one implementation was found
     */
    private Class<?> getImplementationForInterface(Field field) throws IllegalArgumentException {
        if (userSetInterface2Class.containsKey(field.getType())) {
            Class<?> userSetClass = userSetInterface2Class.get(field.getType());
            LOGGER.trace("Using user-set implementation {}", userSetClass);
            return userSetClass;
        }
        if (missingImplementations.contains(field.getType())) {
            LOGGER.trace("Not trying again to find missing implementation, leaving out: {}", field.getName());
            return null;
        }

        Package typePackage = field.getType().getPackage();
        Reflections reflections = package2Reflection.get(typePackage);
        if (reflections == null) {
            if (lookupContext.getPackageScope() == PackageScope.SUBPACKAGES_ONLY) {
                reflections = new Reflections(typePackage.getName());
            } else if (lookupContext.getCustomPackage() != null) {
                reflections = new Reflections(lookupContext.getCustomPackage());
            } else {
                // PackageScope.WHOLE_CLASSPATH
                reflections = new Reflections("");
            }
            package2Reflection.put(typePackage, reflections);
        }
        Set<?> implementations = reflections.getSubTypesOf(field.getType());
        // Filter out interfaces
        for (Iterator<?> iterator = implementations.iterator(); iterator.hasNext();) {
            Object object = iterator.next();
            if (((Class<?>) object).isInterface()) {
                iterator.remove();
            }
        }

        LOGGER.trace("Found implementations: {}", implementations);

        if (implementations.size() == 1) {
            Class<?> implementation = (Class<?>) implementations.iterator().next();
            return implementation;
        } else if (implementations.size() == 0) {
            LOGGER.trace("No implementation found, leaving out: {}", field.getName());
            missingImplementations.add(field.getType());
            return null;
        } else {
            throw new IllegalArgumentException(
                    "More then one implementation found for '" + field.getType() + "' define one by calling setImplementingClassForInterface() or setImplementationForClassOrInterface()");
        }
    }

    /**
     * Sets an implementation object for a class or interface which should be used. This overrides the automatic lookup
     * for the given <code>classOrInterface</code> and could be used for injecting mocks.
     *
     * @param classOrInterface
     *            type for which the object should be used
     * @param object
     *            the object
     */
    public <T> void setImplementationForClassOrInterface(Class<? extends T> classOrInterface, T object) {
        if (classOrInterface.isInterface()) {
            // set class to use for interface
            setImplementingClassForInterface(classOrInterface, object.getClass());
        }
        // set object to use for class
        classOrInterface2Instance.put(classOrInterface, object);
    }

    /**
     * Sets the implementing class for an interface.
     *
     * @param interfaceClass
     *            the interface class
     * @param implementationClass
     *            the implementing class
     */
    public void setImplementingClassForInterface(Class<?> interfaceClass, Class<?> implementationClass) {
        if (!interfaceClass.isInterface()) {
            throw new IllegalArgumentException("First parameter must be an interface");
        }
        if (implementationClass.isInterface()) {
            throw new IllegalArgumentException("Second parameter must be a class");
        }
        if (!interfaceClass.isAssignableFrom(implementationClass)) {
            throw new IllegalArgumentException("The class must implement the interface");
        }
        userSetInterface2Class.put(interfaceClass, implementationClass);
    }

    /**
     * Setting a mock object is a bit more complicating as it introduces one extra inheritance level.
     *
     * @param clazz
     *            the class to mock
     * @param mock
     *            the mock for the class
     */
    public <T> void setMock(Class<? extends T> clazz, T mock) {
        setImplementingClassForInterface(clazz, mock.getClass());
        // mock.getClass is not returning the real class which means the mock cannot be found in the map later
        setImplementationForClassOrInterface(mock.getClass(), mock);
    }
}
