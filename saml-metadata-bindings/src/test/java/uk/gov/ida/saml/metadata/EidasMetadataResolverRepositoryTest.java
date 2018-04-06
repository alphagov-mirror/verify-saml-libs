package uk.gov.ida.saml.metadata;

import com.codahale.metrics.MetricRegistry;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import io.dropwizard.setup.Environment;
import net.minidev.json.JSONObject;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import org.apache.commons.codec.binary.Base64;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;
import uk.gov.ida.common.shared.security.X509CertificateFactory;
import uk.gov.ida.saml.core.test.TestCertificateStrings;
import uk.gov.ida.saml.metadata.factories.DropwizardMetadataResolverFactory;
import uk.gov.ida.saml.metadata.factories.MetadataSignatureTrustEngineFactory;
import uk.gov.ida.shared.utils.datetime.DateTimeFreezer;

import java.security.KeyStoreException;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class EidasMetadataResolverRepositoryTest {

    @Mock
    private EidasTrustAnchorResolver trustAnchorResolver;

    @Mock
    private Environment environment;

    @Mock
    private EidasMetadataConfiguration metadataConfiguration;

    @Mock
    private DropwizardMetadataResolverFactory dropwizardMetadataResolverFactory;

    @Mock
    private Timer timer;

    @Mock
    private MetadataResolver metadataResolver;

    @Mock
    private MetadataSignatureTrustEngineFactory metadataSignatureTrustEngineFactory;

    @Mock
    private ExplicitKeySignatureTrustEngine explicitKeySignatureTrustEngine;

    @Mock
    private MetricRegistry metricRegistry;

    @Captor
    private ArgumentCaptor<MetadataResolverConfiguration> metadataResolverConfigurationCaptor;

    private EidasMetadataResolverRepository metadataResolverRepository;

    private List<JWK> trustAnchors;

    @Before
    public void setUp() throws CertificateException, SignatureException, ParseException, JOSEException, ComponentInitializationException {
        trustAnchors = new ArrayList<>();
        when(trustAnchorResolver.getTrustAnchors()).thenReturn(trustAnchors);
        when(dropwizardMetadataResolverFactory.createMetadataResolver(eq(environment), any())).thenReturn(metadataResolver);
        when(metadataSignatureTrustEngineFactory.createSignatureTrustEngine(metadataResolver)).thenReturn(explicitKeySignatureTrustEngine);
    }

    @After
    public void tearDown() {
        DateTimeFreezer.unfreezeTime();
    }

    @Test
    public void shouldCreateMetadataResolverWhenTrustAnchorIsValid() throws ParseException, KeyStoreException, CertificateEncodingException {
        JWK trustAnchor = createJWK("http://signin.gov.uk/entity/id", Arrays.asList(TestCertificateStrings.METADATA_SIGNING_A_PUBLIC_CERT,
                TestCertificateStrings.METADATA_SIGNING_B_PUBLIC_CERT));
        trustAnchors.add(trustAnchor);
        metadataResolverRepository = new EidasMetadataResolverRepository(trustAnchorResolver, environment, metadataConfiguration,
                dropwizardMetadataResolverFactory, timer, metadataSignatureTrustEngineFactory);

        verify(dropwizardMetadataResolverFactory).createMetadataResolver(eq(environment), metadataResolverConfigurationCaptor.capture());
        MetadataResolver createdMetadataResolver = metadataResolverRepository.getMetadataResolver(trustAnchor.getKeyID()).get();
        MetadataResolverConfiguration metadataResolverConfiguration = metadataResolverConfigurationCaptor.getValue();
        byte[] expectedTrustStoreCertificate = trustAnchor.getX509CertChain().get(0).decode();
        byte[] expectedTrustStoreCACertificate = trustAnchor.getX509CertChain().get(1).decode();
        byte[] actualTrustStoreCertificate = metadataResolverConfiguration.getTrustStore().getCertificate("certificate-0").getEncoded();
        byte[] actualTrustStoreCACertificate = metadataResolverConfiguration.getTrustStore().getCertificate("certificate-1").getEncoded();

        assertThat(createdMetadataResolver).isEqualTo(metadataResolver);
        assertArrayEquals(expectedTrustStoreCertificate, actualTrustStoreCertificate);
        assertArrayEquals(expectedTrustStoreCACertificate, actualTrustStoreCACertificate);
        assertThat(metadataResolverConfiguration.getUri().toString()).isEqualTo("http://signin.gov.uk/entity/id");
        assertThat(metadataResolverRepository.getSignatureTrustEngine(trustAnchor.getKeyID())).isEqualTo(Optional.of(explicitKeySignatureTrustEngine));
    }

    @Test
    public void shouldNotCreateMetadataResolverWhenCertificateIsInvalid() throws ParseException {
        String entityId = "http://signin.gov.uk/entity-id";
        trustAnchors.add(createJWK(entityId, Collections.singletonList(TestCertificateStrings.UNCHAINED_PUBLIC_CERT)));
        metadataResolverRepository = new EidasMetadataResolverRepository(trustAnchorResolver, environment, metadataConfiguration,
                dropwizardMetadataResolverFactory, timer, metadataSignatureTrustEngineFactory);

        assertThat(metadataResolverRepository.getMetadataResolver(entityId)).isEmpty();
        assertThat(metadataResolverRepository.getSignatureTrustEngine(entityId)).isEmpty();
    }

    @Test
    public void shouldUpdateListOfMetadataResolversWhenRefreshing() throws ParseException {
        String toRemoveEntityId = "http://signin.gov.uk/entity-id";
        String toAddEntityId = "http://signin.gov.uk/new-entity-id";
        trustAnchors.add(createJWK(toRemoveEntityId, Collections.singletonList(TestCertificateStrings.METADATA_SIGNING_A_PUBLIC_CERT)));

        DateTime timeNow = DateTime.now();
        DateTimeFreezer.freezeTime(timeNow);

        metadataResolverRepository = new EidasMetadataResolverRepository(trustAnchorResolver, environment, metadataConfiguration,
                dropwizardMetadataResolverFactory, timer, metadataSignatureTrustEngineFactory);
        when(environment.metrics()).thenReturn(metricRegistry);

        trustAnchors.remove(0);
        trustAnchors.add(createJWK(toAddEntityId, Collections.singletonList(TestCertificateStrings.METADATA_SIGNING_A_PUBLIC_CERT)));

        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        runScheduledTask();

        assertThat(metadataResolverRepository.getMetadataResolver(toRemoveEntityId)).isEmpty();
        assertThat(metadataResolverRepository.getMetadataResolver(toAddEntityId)).isPresent();

        String expectedToRemoveClientId = metadataResolverRepository.getClientName(toRemoveEntityId);
        String expectedToAddClientId = metadataResolverRepository.getClientName(toAddEntityId);

        verify(dropwizardMetadataResolverFactory, times(2)).createMetadataResolver(any(), metadataResolverConfigurationCaptor.capture());
        verify(environment.metrics()).remove(stringArgumentCaptor.capture());

        assertThat(stringArgumentCaptor.getValue()).isEqualTo(expectedToRemoveClientId);
        assertThat(metadataResolverConfigurationCaptor.getValue().getJerseyClientName()).isEqualTo(expectedToAddClientId);
    }

    private void runScheduledTask() {
        ArgumentCaptor<TimerTask> argumentCaptor = ArgumentCaptor.forClass(TimerTask.class);
        verify(timer).schedule(argumentCaptor.capture(), anyLong());
        TimerTask value = argumentCaptor.getValue();
        value.run();
    }

    private JWK createJWK(String entityId, List<String> certificates) throws ParseException {
        RSAPublicKey publicKey = (RSAPublicKey) new X509CertificateFactory().createCertificate(certificates.get(0)).getPublicKey();

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("kty", "RSA");
        jsonObject.put("key_ops", Collections.singletonList("verify"));
        jsonObject.put("kid", entityId);
        jsonObject.put("alg", "RS256");
        jsonObject.put("e", new String (Base64.encodeInteger(publicKey.getPublicExponent())));
        jsonObject.put("n", new String (Base64.encodeInteger(publicKey.getModulus())));
        jsonObject.put("x5c", certificates);

        return JWK.parse(jsonObject.toJSONString());
    }
}
