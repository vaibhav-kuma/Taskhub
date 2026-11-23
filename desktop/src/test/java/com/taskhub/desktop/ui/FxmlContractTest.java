package com.taskhub.desktop.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Static contract test for the FXML views.
 *
 * <p>FXML wiring errors are invisible to the Java compiler and only explode when a user opens the
 * window. This test parses each view, resolves the declared controller, and verifies that every
 * {@code fx:id} maps to a field and every handler reference maps to a method. It runs without a
 * display, so it executes in CI on every commit.
 */
class FxmlContractTest {

    private static final String FX_NS = "http://javafx.com/fxml/1";
    private static final List<String> VIEWS = List.of("/fxml/login.fxml", "/fxml/main.fxml");

    @Test
    void everyViewIsWiredToItsController() throws Exception {
        for (String view : VIEWS) {
            Document document = parse(view);
            Element root = document.getDocumentElement();

            String controllerName = root.getAttributeNS(FX_NS, "controller");
            assertThat(controllerName).as("%s declares a controller", view).isNotBlank();

            Class<?> controller = Class.forName(controllerName);
            assertThat(controller.getPackageName()).as("%s controller package", view)
                    .isEqualTo("com.taskhub.desktop.ui");

            NodeList all = document.getElementsByTagName("*");
            List<String> missingIds = new ArrayList<>();
            List<String> missingHandlers = new ArrayList<>();

            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);

                String id = element.getAttributeNS(FX_NS, "id");
                if (!id.isBlank() && !hasField(controller, id)) {
                    missingIds.add(id);
                }

                var attributes = element.getAttributes();
                for (int a = 0; a < attributes.getLength(); a++) {
                    var attribute = attributes.item(a);
                    if (!FX_NS.equals(attribute.getNamespaceURI())) {
                        continue;
                    }
                    String name = attribute.getLocalName();
                    String value = attribute.getNodeValue();
                    if (name != null && name.startsWith("on") && value.startsWith("#")) {
                        String method = value.substring(1);
                        if (!hasHandler(controller, method)) {
                            missingHandlers.add(element.getTagName() + "." + name + " -> " + method);
                        }
                    }
                }
            }

            assertThat(missingIds).as("%s: every fx:id has a controller field", view).isEmpty();
            assertThat(missingHandlers).as("%s: every handler maps to a controller method", view).isEmpty();
        }
    }

    @Test
    void everyViewHasAStylesheetInTheBundle() {
        for (String view : VIEWS) {
            assertThat(FxmlContractTest.class.getResource("/css/app.css")).isNotNull();
            assertThat(FxmlContractTest.class.getResource(view)).isNotNull();
        }
    }

    private static Document parse(String resource) throws Exception {
        try (InputStream in = FxmlContractTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                fail("Missing FXML resource: " + resource);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        return false;
    }

    private static boolean hasHandler(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() <= 1) {
                return true;
            }
        }
        return false;
    }
}
