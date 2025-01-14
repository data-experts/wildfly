/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.extension.undertow;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.extension.undertow.Capabilities.CAPABILITY_APPLICATION_SECURITY_DOMAIN;
import static org.wildfly.extension.undertow.Capabilities.CAPABILITY_APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS;
import static org.wildfly.extension.undertow.Capabilities.REF_HTTP_AUTHENTICATION_FACTORY;
import static org.wildfly.extension.undertow.Capabilities.REF_SECURITY_DOMAIN;
import static org.wildfly.security.http.HttpConstants.BASIC_NAME;
import static org.wildfly.security.http.HttpConstants.CLIENT_CERT_NAME;
import static org.wildfly.security.http.HttpConstants.DIGEST_NAME;
import static org.wildfly.security.http.HttpConstants.FORM_NAME;

import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.CapabilityServiceTarget;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationContext.AttachmentKey;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.ServiceRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.StringListAttributeDefinition;
import org.jboss.as.controller.access.constraint.ApplicationTypeConfig;
import org.jboss.as.controller.access.constraint.SensitivityClassification;
import org.jboss.as.controller.access.management.ApplicationTypeAccessConstraintDefinition;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.metadata.javaee.jboss.RunAsIdentityMetaData;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.clustering.service.ServiceConfigurator;
import org.wildfly.clustering.web.container.SecurityDomainSingleSignOnManagementConfiguration;
import org.wildfly.clustering.web.container.SecurityDomainSingleSignOnManagementProvider;
import org.wildfly.elytron.web.undertow.server.servlet.AuthenticationManager;
import org.wildfly.extension.undertow.security.jacc.JACCAuthorizationManager;
import org.wildfly.extension.undertow.sso.elytron.NonDistributableSingleSignOnManagementProvider;
import org.wildfly.extension.undertow.sso.elytron.SingleSignOnIdentifierFactory;
import org.wildfly.security.auth.server.HttpAuthenticationFactory;
import org.wildfly.security.auth.server.MechanismConfiguration;
import org.wildfly.security.auth.server.MechanismConfigurationSelector;
import org.wildfly.security.auth.server.MechanismRealmConfiguration;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.cache.IdentityCache;
import org.wildfly.security.http.HttpExchangeSpi;
import org.wildfly.security.http.HttpServerAuthenticationMechanismFactory;
import org.wildfly.security.http.basic.BasicMechanismFactory;
import org.wildfly.security.http.bearer.BearerMechanismFactory;
import org.wildfly.security.http.cert.ClientCertMechanismFactory;
import org.wildfly.security.http.digest.DigestMechanismFactory;
import org.wildfly.security.http.external.ExternalMechanismFactory;
import org.wildfly.security.http.form.FormMechanismFactory;
import org.wildfly.security.http.spnego.SpnegoMechanismFactory;
import org.wildfly.security.http.util.AggregateServerMechanismFactory;
import org.wildfly.security.http.util.FilterServerMechanismFactory;
import org.wildfly.security.http.util.sso.ProgrammaticSingleSignOnCache;
import org.wildfly.security.http.util.sso.SingleSignOnServerMechanismFactory;
import org.wildfly.security.http.util.sso.SingleSignOnConfiguration;
import org.wildfly.security.http.util.sso.SingleSignOnSessionFactory;
import org.wildfly.security.manager.WildFlySecurityManager;

import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.LoginConfig;

/**
 * A {@link ResourceDefinition} to define the mapping from a security domain as specified in a web application
 * to an {@link HttpAuthenticationFactory} plus additional policy information.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class ApplicationSecurityDomainDefinition extends PersistentResourceDefinition {
    static final PathElement PATH_ELEMENT = PathElement.pathElement(Constants.APPLICATION_SECURITY_DOMAIN);
    private static final Predicate<String> SERVLET_MECHANISM;

    static {
        Set<String> defaultMechanisms = new HashSet<>(4);
        defaultMechanisms.add(BASIC_NAME);
        defaultMechanisms.add(CLIENT_CERT_NAME);
        defaultMechanisms.add(DIGEST_NAME);
        defaultMechanisms.add(FORM_NAME);

        SERVLET_MECHANISM = defaultMechanisms::contains;
    }

    static final RuntimeCapability<Void> APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY = RuntimeCapability
            .Builder.of(CAPABILITY_APPLICATION_SECURITY_DOMAIN, true, BiFunction.class)
            .build();

    static final RuntimeCapability<Void> APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS_CAPABILITY = RuntimeCapability
            .Builder.of(CAPABILITY_APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS, true)
            .build();

    static final SimpleAttributeDefinition HTTP_AUTHENTICATION_FACTORY = new SimpleAttributeDefinitionBuilder(Constants.HTTP_AUTHENTICATION_FACTORY, ModelType.STRING, false)
            .setMinSize(1)
            .setRestartAllServices()
            .setCapabilityReference(REF_HTTP_AUTHENTICATION_FACTORY)
            .setAccessConstraints(SensitiveTargetAccessConstraintDefinition.AUTHENTICATION_FACTORY_REF)
            .setAlternatives(Constants.SECURITY_DOMAIN)
            .build();

    static final SimpleAttributeDefinition OVERRIDE_DEPLOYMENT_CONFIG = new SimpleAttributeDefinitionBuilder(Constants.OVERRIDE_DEPLOYMENT_CONFIG, ModelType.BOOLEAN, true)
            .setDefaultValue(ModelNode.FALSE)
            .setRestartAllServices()
            .setRequires(Constants.HTTP_AUTHENTICATION_FACTORY)
            .build();

    static final SimpleAttributeDefinition SECURITY_DOMAIN = new SimpleAttributeDefinitionBuilder(Constants.SECURITY_DOMAIN, ModelType.STRING, false)
            .setMinSize(1)
            .setRestartAllServices()
            .setCapabilityReference(REF_SECURITY_DOMAIN)
            .setAccessConstraints(SensitiveTargetAccessConstraintDefinition.ELYTRON_SECURITY_DOMAIN_REF)
            .setAlternatives(Constants.HTTP_AUTHENTICATION_FACTORY)
            .build();

    private static final StringListAttributeDefinition REFERENCING_DEPLOYMENTS = new StringListAttributeDefinition.Builder(Constants.REFERENCING_DEPLOYMENTS)
            .setStorageRuntime()
            .build();

    static final SimpleAttributeDefinition ENABLE_JACC = new SimpleAttributeDefinitionBuilder(Constants.ENABLE_JACC, ModelType.BOOLEAN, true)
            .setDefaultValue(ModelNode.FALSE)
            .setMinSize(1)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition ENABLE_JASPI = new SimpleAttributeDefinitionBuilder(Constants.ENABLE_JASPI, ModelType.BOOLEAN, true)
            .setDefaultValue(ModelNode.TRUE)
            .setAllowExpression(true)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition INTEGRATED_JASPI = new SimpleAttributeDefinitionBuilder(Constants.INTEGRATED_JASPI, ModelType.BOOLEAN, true)
            .setDefaultValue(ModelNode.TRUE)
            .setAllowExpression(true)
            .setRestartAllServices()
            .build();

    static final Collection<AttributeDefinition> ATTRIBUTES = List.of(SECURITY_DOMAIN, HTTP_AUTHENTICATION_FACTORY, OVERRIDE_DEPLOYMENT_CONFIG, ENABLE_JACC, ENABLE_JASPI, INTEGRATED_JASPI);

    private static final AttachmentKey<KnownDeploymentsApi> KNOWN_DEPLOYMENTS_KEY = AttachmentKey.create(KnownDeploymentsApi.class);

    private final Set<String> knownApplicationSecurityDomains;

    ApplicationSecurityDomainDefinition(Set<String> knownApplicationSecurityDomains) {
        this(knownApplicationSecurityDomains, new AddHandler(knownApplicationSecurityDomains));
    }

    private ApplicationSecurityDomainDefinition(Set<String> knownApplicationSecurityDomains, AbstractAddStepHandler addHandler) {
        super(new SimpleResourceDefinition.Parameters(PATH_ELEMENT, UndertowExtension.getResolver(PATH_ELEMENT.getKey()))
                .setCapabilities(APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY)
                .addAccessConstraints(new SensitiveTargetAccessConstraintDefinition(new SensitivityClassification(UndertowExtension.SUBSYSTEM_NAME, Constants.APPLICATION_SECURITY_DOMAIN, false, false, false)),
                        new ApplicationTypeAccessConstraintDefinition(new ApplicationTypeConfig(UndertowExtension.SUBSYSTEM_NAME, Constants.APPLICATION_SECURITY_DOMAIN)))
                .setAddHandler(addHandler)
                .setRemoveHandler(new RemoveHandler(knownApplicationSecurityDomains, addHandler))
        );
        this.knownApplicationSecurityDomains = knownApplicationSecurityDomains;
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        if (resourceRegistration.getProcessType().isServer()) {
            resourceRegistration.registerReadOnlyAttribute(REFERENCING_DEPLOYMENTS, new ReferencingDeploymentsHandler());
        }
    }

    @Override
    protected List<? extends PersistentResourceDefinition> getChildren() {
        return Collections.singletonList(new ApplicationSecurityDomainSingleSignOnDefinition());
    }

    private static class ReferencingDeploymentsHandler implements OperationStepHandler {

        @Override
        public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
            if (context.isDefaultRequiresRuntime()) {
                context.addStep((ctx, op) -> {
                    final KnownDeploymentsApi knownDeploymentsApi = context.getCapabilityRuntimeAPI(
                            CAPABILITY_APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS, ctx.getCurrentAddressValue(),
                            KnownDeploymentsApi.class);

                    ModelNode deploymentList = new ModelNode();
                    for (String current : knownDeploymentsApi.getKnownDeployments()) {
                        deploymentList.add(current);
                    }

                    context.getResult().set(deploymentList);
                }, OperationContext.Stage.RUNTIME);
            }
        }

    }

    private static class AddHandler extends AbstractAddStepHandler {
        private final Set<String> knownApplicationSecurityDomains;
        private final SecurityDomainSingleSignOnManagementProvider provider;

        private AddHandler(Set<String> knownApplicationSecurityDomains) {
            super(ATTRIBUTES);
            this.knownApplicationSecurityDomains = knownApplicationSecurityDomains;
            Iterator<SecurityDomainSingleSignOnManagementProvider> providers = ServiceLoader.load(SecurityDomainSingleSignOnManagementProvider.class, SecurityDomainSingleSignOnManagementProvider.class.getClassLoader()).iterator();
            this.provider = providers.hasNext() ? providers.next() : NonDistributableSingleSignOnManagementProvider.INSTANCE;
        }

        /* (non-Javadoc)
         * @see org.jboss.as.controller.AbstractAddStepHandler#populateModel(org.jboss.as.controller.OperationContext, org.jboss.dmr.ModelNode, org.jboss.as.controller.registry.Resource)
         */
        @Override
        protected void populateModel(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            super.populateModel(context, operation, resource);
            this.knownApplicationSecurityDomains.add(context.getCurrentAddressValue());
        }

        @Override
        protected void recordCapabilitiesAndRequirements(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            super.recordCapabilitiesAndRequirements(context, operation, resource);
            KnownDeploymentsApi knownDeployments = new KnownDeploymentsApi();
            context.registerCapability(RuntimeCapability.Builder
                    .of(CAPABILITY_APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS, true, knownDeployments).build()
                    .fromBaseCapability(context.getCurrentAddressValue()));
            context.attach(KNOWN_DEPLOYMENTS_KEY, knownDeployments);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            ModelNode model = resource.getModel();
            CapabilityServiceTarget target = context.getCapabilityServiceTarget();

            final String securityDomain = SECURITY_DOMAIN.resolveModelAttribute(context, model).asStringOrNull();
            final String httpServerMechanismFactory = HTTP_AUTHENTICATION_FACTORY.resolveModelAttribute(context, model).asStringOrNull();
            boolean overrideDeploymentConfig = OVERRIDE_DEPLOYMENT_CONFIG.resolveModelAttribute(context, model).asBoolean();
            boolean enableJacc = ENABLE_JACC.resolveModelAttribute(context, model).asBoolean();
            boolean enableJaspi = ENABLE_JASPI.resolveModelAttribute(context, model).asBoolean();
            boolean integratedJaspi = INTEGRATED_JASPI.resolveModelAttribute(context, model).asBoolean();

            String securityDomainName = context.getCurrentAddressValue();

            ServiceName applicationSecurityDomainName = APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY.getCapabilityServiceName(context.getCurrentAddress());
            ServiceName securityDomainServiceName = applicationSecurityDomainName.append(Constants.SECURITY_DOMAIN);

            ServiceBuilder<?> serviceBuilder = target
                    .addService(applicationSecurityDomainName)
                    .setInitialMode(Mode.LAZY);

            final Supplier<HttpAuthenticationFactory> httpAuthenticationFactorySupplier;
            final Supplier<SecurityDomain> securityDomainSupplier;
            if (httpServerMechanismFactory != null) {
                httpAuthenticationFactorySupplier = serviceBuilder.requires(context.getCapabilityServiceName(REF_HTTP_AUTHENTICATION_FACTORY, HttpAuthenticationFactory.class, httpServerMechanismFactory));
                securityDomainSupplier = null;
            } else {
                securityDomainSupplier = serviceBuilder.requires(context.getCapabilityServiceName(REF_SECURITY_DOMAIN, SecurityDomain.class, securityDomain));
                httpAuthenticationFactorySupplier = null;
            }

            final Supplier<UnaryOperator<HttpServerAuthenticationMechanismFactory>> transformerSupplier;
            final BiFunction<HttpExchangeSpi, String, IdentityCache> identityCacheSupplier;
            if (resource.hasChild(SingleSignOnDefinition.PATH_ELEMENT)) {
                ModelNode ssoModel = resource.getChild(SingleSignOnDefinition.PATH_ELEMENT).getModel();

                String cookieName = SingleSignOnDefinition.Attribute.COOKIE_NAME.resolveModelAttribute(context, ssoModel).asString();
                String domain = null;
                if (SingleSignOnDefinition.Attribute.DOMAIN.resolveModelAttribute(context, ssoModel).isDefined()) {
                    domain = SingleSignOnDefinition.Attribute.DOMAIN.resolveModelAttribute(context, ssoModel).asString();
                }
                String path = SingleSignOnDefinition.Attribute.PATH.resolveModelAttribute(context, ssoModel).asString();
                boolean httpOnly = SingleSignOnDefinition.Attribute.HTTP_ONLY.resolveModelAttribute(context, ssoModel).asBoolean();
                boolean secure = SingleSignOnDefinition.Attribute.SECURE.resolveModelAttribute(context, ssoModel).asBoolean();
                SingleSignOnConfiguration singleSignOnConfiguration = new SingleSignOnConfiguration(cookieName, domain, path, httpOnly, secure);

                ServiceName managerServiceName = new SingleSignOnManagerServiceNameProvider(securityDomainName).getServiceName();
                Supplier<String> generator = new SingleSignOnIdentifierFactory();

                SecurityDomainSingleSignOnManagementConfiguration configuration = new SecurityDomainSingleSignOnManagementConfiguration() {
                    @Override
                    public String getSecurityDomainName() {
                        return securityDomainName;
                    }

                    @Override
                    public Supplier<String> getIdentifierGenerator() {
                        return generator;
                    }
                };
                this.provider.getServiceConfigurator(managerServiceName, configuration).configure(context).build(target).setInitialMode(ServiceController.Mode.ON_DEMAND).install();

                ServiceConfigurator factoryConfigurator = new SingleSignOnSessionFactoryServiceConfigurator(securityDomainName).configure(context, ssoModel);
                factoryConfigurator.build(target).setInitialMode(ServiceController.Mode.ON_DEMAND).install();

                Supplier<SingleSignOnSessionFactory> singleSignOnSessionFactorySupplier = serviceBuilder.requires(factoryConfigurator.getServiceName());
                UnaryOperator<HttpServerAuthenticationMechanismFactory> transformer = (factory) -> new SingleSignOnServerMechanismFactory(factory, singleSignOnSessionFactorySupplier.get(), singleSignOnConfiguration);

                identityCacheSupplier = (HttpExchangeSpi httpExchangeSpi, String mechanismName) -> ProgrammaticSingleSignOnCache.newInstance(httpExchangeSpi, mechanismName, singleSignOnSessionFactorySupplier.get(), singleSignOnConfiguration);
                transformerSupplier = () -> transformer;

            } else {
                identityCacheSupplier = null;
                transformerSupplier = () -> null;
            }

            Consumer<BiFunction<DeploymentInfo, Function<String, RunAsIdentityMetaData>, Registration>> deploymentConsumer = serviceBuilder.provides(applicationSecurityDomainName);
            Consumer<SecurityDomain> securityDomainConsumer = serviceBuilder.provides(securityDomainServiceName);
            ApplicationSecurityDomainService service = new ApplicationSecurityDomainService(overrideDeploymentConfig,
                    enableJacc, enableJaspi, integratedJaspi, httpAuthenticationFactorySupplier, securityDomainSupplier,
                    transformerSupplier, identityCacheSupplier, deploymentConsumer, securityDomainConsumer);
            serviceBuilder.setInstance(service);
            serviceBuilder.install();

            KnownDeploymentsApi knownDeploymentsApi = context.getAttachment(KNOWN_DEPLOYMENTS_KEY);
            knownDeploymentsApi.setApplicationSecurityDomainService(service);
        }

    }

    private static HttpAuthenticationFactory toHttpAuthenticationFactory(final SecurityDomain securityDomain, final String realmName) {
        final HttpServerAuthenticationMechanismFactory mechanismFactory = new FilterServerMechanismFactory(
                new AggregateServerMechanismFactory(new BasicMechanismFactory(), new BearerMechanismFactory(),
                        new ClientCertMechanismFactory(), new DigestMechanismFactory(), new ExternalMechanismFactory(),
                        new FormMechanismFactory(), new SpnegoMechanismFactory()), SERVLET_MECHANISM);
        return HttpAuthenticationFactory.builder().setFactory(mechanismFactory)
                .setSecurityDomain(securityDomain)
                .setMechanismConfigurationSelector(
                        MechanismConfigurationSelector.constantSelector(realmName == null ? MechanismConfiguration.EMPTY
                                : MechanismConfiguration.builder()
                                        .addMechanismRealm(
                                                MechanismRealmConfiguration.builder().setRealmName(realmName).build())
                                        .build()))
                .build();
    }

    private static class RemoveHandler extends ServiceRemoveStepHandler {
        private final Set<String> knownApplicationSecurityDomains;

        /**
         * @param addOperation
         */
        protected RemoveHandler(Set<String> knownApplicationSecurityDomains, AbstractAddStepHandler addOperation) {
            super(addOperation, APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY, APPLICATION_SECURITY_DOMAIN_KNOWN_DEPLOYMENTS_CAPABILITY);
            this.knownApplicationSecurityDomains = knownApplicationSecurityDomains;
        }

        @Override
        protected void performRemove(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
            super.performRemove(context, operation, model);
            this.knownApplicationSecurityDomains.remove(context.getCurrentAddressValue());
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) {
            super.performRuntime(context, operation, model);
            if (context.isResourceServiceRestartAllowed()) {
                final String securityDomainName = context.getCurrentAddressValue();
                context.removeService(new SingleSignOnManagerServiceNameProvider(securityDomainName).getServiceName());
                context.removeService(new SingleSignOnSessionFactoryServiceNameProvider(securityDomainName).getServiceName());
            }
        }

        @Override
        protected ServiceName serviceName(String name) {
            RuntimeCapability<?> dynamicCapability = APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY.fromBaseCapability(name);
            return dynamicCapability.getCapabilityServiceName(BiFunction.class); // no-arg getCapabilityServiceName() would be fine too
        }

    }

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return ATTRIBUTES;
    }

    Predicate<String> getKnownSecurityDomainPredicate() {
        return knownApplicationSecurityDomains::contains;
    }

    private static class ApplicationSecurityDomainService implements Service, BiFunction<DeploymentInfo, Function<String, RunAsIdentityMetaData>, Registration> {

        private final Supplier<HttpAuthenticationFactory> httpAuthenticationFactorySupplier;
        private final Supplier<SecurityDomain> securityDomainSupplier;
        private final Supplier<UnaryOperator<HttpServerAuthenticationMechanismFactory>> singleSignOnTransformerSupplier;
        private final BiFunction<HttpExchangeSpi, String, IdentityCache> identityCacheSupplier;

        private final Consumer<BiFunction<DeploymentInfo, Function<String, RunAsIdentityMetaData>, Registration>> deploymentConsumer;
        private final Consumer<SecurityDomain> securityDomainConsumer;

        private final boolean overrideDeploymentConfig;
        private final Set<RegistrationImpl> registrations = new HashSet<>();
        private final boolean enableJacc;
        private final boolean enableJaspi;
        private final boolean integratedJaspi;

        private volatile HttpAuthenticationFactory httpAuthenticationFactory;
        private volatile SecurityDomain securityDomain;

        private ApplicationSecurityDomainService(final boolean overrideDeploymentConfig, boolean enableJacc, boolean enableJaspi, boolean integratedJaspi,
                final Supplier<HttpAuthenticationFactory> httpAuthenticationFactorySupplier, final Supplier<SecurityDomain> securityDomainSupplier,
                Supplier<UnaryOperator<HttpServerAuthenticationMechanismFactory>> singleSignOnTransformerSupplier, BiFunction<HttpExchangeSpi, String, IdentityCache> identityCacheSupplier,
                Consumer<BiFunction<DeploymentInfo, Function<String, RunAsIdentityMetaData>, Registration>> deploymentConsumer, Consumer<SecurityDomain> securityDomainConsumer) {
            this.overrideDeploymentConfig = overrideDeploymentConfig;
            this.enableJacc = enableJacc;
            this.enableJaspi = enableJaspi;
            this.integratedJaspi = integratedJaspi;
            this.httpAuthenticationFactorySupplier = httpAuthenticationFactorySupplier;
            this.securityDomainSupplier = securityDomainSupplier;
            this.singleSignOnTransformerSupplier = singleSignOnTransformerSupplier;
            this.identityCacheSupplier = identityCacheSupplier;
            this.deploymentConsumer = deploymentConsumer;
            this.securityDomainConsumer = securityDomainConsumer;
        }

        @Override
        public void start(StartContext context) throws StartException {
            deploymentConsumer.accept(this);
            if (httpAuthenticationFactorySupplier != null) {
                httpAuthenticationFactory = httpAuthenticationFactorySupplier.get();
                securityDomain = httpAuthenticationFactory.getSecurityDomain();
            } else {
                securityDomain = securityDomainSupplier.get();
            }
            securityDomainConsumer.accept(securityDomain);
        }

        @Override
        public void stop(StopContext context) {}

        @Override
        public Registration apply(DeploymentInfo deploymentInfo, Function<String, RunAsIdentityMetaData> runAsMapper) {
            HttpAuthenticationFactory httpAuthenticationFactory = this.httpAuthenticationFactory != null
                    ? this.httpAuthenticationFactory
                    : toHttpAuthenticationFactory(securityDomain, getRealmName(deploymentInfo));
            AuthenticationManager.Builder builder = AuthenticationManager.builder()
                    .setHttpAuthenticationFactory(httpAuthenticationFactory)
                    .setOverrideDeploymentConfig(overrideDeploymentConfig)
                    .setHttpAuthenticationFactoryTransformer(singleSignOnTransformerSupplier.get())
                    .setIdentityCacheSupplier(identityCacheSupplier)
                    .setRunAsMapper(runAsMapper)
                    .setEnableJaspi(enableJaspi)
                    .setIntegratedJaspi(integratedJaspi);

            if (enableJacc) {
                builder.setAuthorizationManager(JACCAuthorizationManager.INSTANCE);
            }

            AuthenticationManager authenticationManager = builder.build();
            authenticationManager.configure(deploymentInfo);

            RegistrationImpl registration = new RegistrationImpl(deploymentInfo);
            synchronized(registrations) {
                registrations.add(registration);
            }
            return registration;
        }

        private String getRealmName(final DeploymentInfo deploymentInfo) {
            LoginConfig loginConfig = deploymentInfo.getLoginConfig();
            return loginConfig != null ? loginConfig.getRealmName() : null;
        }

        private List<String> getDeployments() {
            synchronized (registrations) {
                List<String> deployments = new ArrayList<>(registrations.size());
                for (RegistrationImpl registration : registrations) {
                    deployments.add(registration.deploymentInfo.getDeploymentName());
                }
                return deployments;
            }
        }

        private class RegistrationImpl implements Registration {

            final DeploymentInfo deploymentInfo;

            private RegistrationImpl(DeploymentInfo deploymentInfo) {
                this.deploymentInfo = deploymentInfo;
            }

            @Override
            public void cancel() {
                if (WildFlySecurityManager.isChecking()) {
                    doPrivileged((PrivilegedAction<Void>) () -> {
                        SecurityDomain.unregisterClassLoader(deploymentInfo.getClassLoader());
                        return null;
                    });
                } else {
                    SecurityDomain.unregisterClassLoader(deploymentInfo.getClassLoader());
                }
                synchronized(registrations) {
                    registrations.remove(this);
                }
            }

        }

    }

    public interface Registration {

        /**
         * Cancel the registration.
         */
        void cancel();

    }

    private static class KnownDeploymentsApi {

        private volatile ApplicationSecurityDomainService service;

        List<String> getKnownDeployments() {
            return service != null ? service.getDeployments() : Collections.emptyList();

        }

        void setApplicationSecurityDomainService(final ApplicationSecurityDomainService service) {
            this.service = service;
        }
    }

}
