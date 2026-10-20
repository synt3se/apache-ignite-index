package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.services.ServiceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.node.index.AlgorithmKind;
import ru.nsu.fit.sberlab.vectorindex.node.index.IndexDefinition;
import ru.nsu.fit.sberlab.vectorindex.node.service.LocalIndexServiceImpl;

/**
 * Разбор XML узла через Spring в bean, без запуска Ignite: новый список {@code indexes}, сеттеры,
 * сериализация вложенных настроек и совместимость существующих конфигураций Docker.
 */
class IgniteXmlConfigTest {

    @Test
    void multiIndexExampleDefinesIndexesOnSeveralCachesAndFields() throws Exception {
        LocalIndexServiceImpl service = localIndexService(new ClassPathResource("ignite-multi-index.xml"));

        List<IndexDefinition> definitions = service.definitions();

        assertThat(definitions).extracting(IndexDefinition::indexId, IndexDefinition::cacheName,
                IndexDefinition::field, d -> d.spec().dimension(), d -> d.spec().metric(), IndexDefinition::kind)
                .containsExactly(
                        tuple("images", "vectors", "vector", 512, Metric.COSINE, AlgorithmKind.JVECTOR),
                        tuple("title", "docs", "title", 64, Metric.COSINE, AlgorithmKind.FLAT),
                        tuple("body", "docs", "body", 32, Metric.SQUARED_L2, AlgorithmKind.FLAT),
                        tuple("sift", "sift", null, 128, Metric.SQUARED_L2, AlgorithmKind.FLAT));
        assertThat(service.getIndexes().get(0).getJvectorProperties().m()).isEqualTo(32);
        assertSurvivesSerialization(service);
    }

    @Test
    void existingDockerConfigurationsStillDefineTheLegacyDemoIndex() throws Exception {
        for (String path : List.of("../docker/config/ignite-config.xml", "../docker/config-2vm/ignite-config.xml")) {
            Path xml = Path.of(path);
            assertThat(xml).as("existing configuration " + path).exists();
            LocalIndexServiceImpl service = localIndexService(new FileSystemResource(xml.toFile()));

            assertThat(service.definitions()).as(path)
                    .extracting(IndexDefinition::indexId, IndexDefinition::cacheName, IndexDefinition::field,
                            d -> d.spec().dimension(), d -> d.spec().metric(), IndexDefinition::kind)
                    .containsExactly(tuple("vectors", "vectors", "vector", 512, Metric.COSINE, AlgorithmKind.JVECTOR));
            assertSurvivesSerialization(service);
        }
    }

    /** Переменные окружения узла из docker-compose.2vm.yml: без них плейсхолдеры не разрешаются. */
    private static final Map<String, Object> NODE_ENVIRONMENT = Map.of(
            "IGNITE_LOCAL_HOST", "127.0.0.1", "IGNITE_CONSISTENT_ID", "node-1", "IGNITE_COMM_PORT", "47100",
            "IGNITE_DISCO_PORT", "47500", "VM1_IP", "127.0.0.1", "VM2_IP", "127.0.0.1", "IGNITE_THIN_PORT", "10800");

    private static LocalIndexServiceImpl localIndexService(Resource xml) {
        try (GenericXmlApplicationContext context = new GenericXmlApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("node", NODE_ENVIRONMENT));
            context.load(xml);
            context.refresh();
            IgniteConfiguration configuration = context.getBean(IgniteConfiguration.class);
            return Arrays.stream(configuration.getServiceConfiguration())
                    .filter(c -> "LocalIndexService".equals(c.getName()))
                    .map(ServiceConfiguration::getService)
                    .map(LocalIndexServiceImpl.class::cast)
                    .findFirst().orElseThrow();
        }
    }

    /** Сервис уходит на узлы сериализованным: вложенные настройки должны дать те же определения. */
    private static void assertSurvivesSerialization(LocalIndexServiceImpl service) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(service);
        }
        LocalIndexServiceImpl copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (LocalIndexServiceImpl) in.readObject();
        }
        assertThat(copy.definitions()).extracting(IndexDefinition::fingerprint)
                .containsExactlyElementsOf(service.definitions().stream().map(IndexDefinition::fingerprint).toList());
    }
}
