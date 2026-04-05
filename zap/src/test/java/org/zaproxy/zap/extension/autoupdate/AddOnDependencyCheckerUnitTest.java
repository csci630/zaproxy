package org.zaproxy.zap.extension.autoupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zaproxy.zap.control.AddOn;
import org.zaproxy.zap.control.AddOnCollection;

public class AddOnDependencyCheckerUnitTest {
    
    @TempDir Path tempDir;

    @Test
    void shouldCalculateInstallChangesForValidDependencyGraph() throws Exception{
        AddOn dependency = createAvailableAddOn("dep.zap", "1.0.0", null);
        AddOn selected = createAvailableAddOn(
            "main.zap", "1.0.0", manifest -> appendDependency(
                manifest, "dep", null
            )
        );

        AddOnDependencyChecker checker = new AddOnDependencyChecker(AddOnCollection(), AddOnCollection(selected, dependency));

        AddOnDependencyChecker.AddOnChangesResult result = checker.calculateInstallChanges(selected);

        //assertions
        assertThat(result.getSelectedAddOns(), containsInAnyOrder(selected));
        assertThat(result.getInstalls(), containsInAnyOrder(selected, dependency));
        assertThat(result.getOldVersions(), is(empty()));
        assertThat(result.getNewVersions(), is(empty()));
        assertThat(result.getUninstalls(), is(empty()));
        assertThat(result.getOptionalAddOns(), is(empty()));
        assertThat(result.getUnloadExtensions(), is(empty()));
        assertThat(result.getSoftUnloadExtensions(), is(empty()));
        assertThat(result.isNewerJavaVersionRequired(), is(false));
    
    }

    @Test
    void shouldKeepOnlySelectedInstallWhenDependencyVersionDoesNotMatch() throws Exception{
        AddOn dependency = createAvailableAddOn("dep.zap", "1.0.0", null);
        AddOn selected = createAvailableAddOn(
            "main.zap", "1.0.0", manifest -> appendDependency(
                manifest, "dep", "2.*"
            )
        );

        AddOnDependencyChecker checker = new AddOnDependencyChecker(AddOnCollection(), AddOnCollection(selected, dependency));
        AddOnDependencyChecker.AddOnChangesResult result = checker.calculateInstallChanges(selected);

        //assertions
        assertThat(result.getSelectedAddOns(), containsInAnyOrder(selected));
        assertThat(result.getInstalls(), containsInAnyOrder(selected, dependency));
        assertThat(result.getOldVersions(), is(empty()));
        assertThat(result.getNewVersions(), is(empty()));
        assertThat(result.getUninstalls(), is(empty()));
        assertThat(result.getOptionalAddOns(), is(empty()));
        assertThat(result.getUnloadExtensions(), is(empty()));
        assertThat(result.getSoftUnloadExtensions(), is(empty()));
        assertThat(result.isNewerJavaVersionRequired(), is(false));

    }

    @Test
    void shouldReturnOptionalAddOnForExtensionDependency() throws Exception{
        AddOn optional = createAvailableAddOn("opt,zap", "1.0.0", null);
        AddOn selected = createAvailableAddOn(
            "main.zap", "1.0.0", manifest -> appendExtensionWithDependency(
                manifest, "org.example.MainExtension", "opt", null
            )
        );

        AddOnDependencyChecker checker = new AddOnDependencyChecker(AddOnCollection, AddOnCollection(selected, optional));

        AddOnDependencyChecker.AddOnChangesResult result = checker.calculateInstallChanges(selected);

        //assertions
        assertThat(result.getSelectedAddOns(), containsInAnyOrder(selected));
        assertThat(result.getInstalls(), containsInAnyOrder(selected));
        assertThat(result.getOldVersions(), is(empty()));
        assertThat(result.getNewVersions(), is(empty()));
        assertThat(result.getUninstalls(), is(empty()));
        assertThat(result.getOptionalAddOns(), is(empty()));
        assertThat(result.getUnloadExtensions(), is(empty()));
        assertThat(result.getSoftUnloadExtensions(), is(empty()));
        assertThat(result.isNewerJavaVersionRequired(), is(false));
    }

    @Test
    void shouldHandleCyclicDependencyGraphWithoutChangingCurrentOutCome() throws Exception{
        AddOn addOnA = createAvailableAddOn(
            "a.zap", "1.0.0", manifest -> appendDependency(manifest, "b", null)
        );

        AddOn addOnB = createAvailableAddOn(
            "b.zap", "1.0.0", manifest -> appendDependency(manifest, "a", null)
        );

        AddOnDependencyChecker checker = new AddOnDependencyChecker(AddOnCollection(), AddOnCollection(addOnA, addOnB));

        AddOnDependencyChecker.AddOnChangesResult result = checker.calculateInstallChanges(addonA);

        //assertions
        assertThat(result.getSelectedAddOns(), containsInAnyOrder(selected));
        assertThat(result.getInstalls(), containsInAnyOrder(selected));
        assertThat(result.getOptionalAddOns(), containsInAnyOrder(optional));
        assertThat(result.getOldVersions(), is(empty()));
        assertThat(result.getNewVersions(), is(empty()));
        assertThat(result.getUninstalls(), is(empty()));
        assertThat(result.getUnloadExtensions(), is(empty()));
        assertThat(result.getSoftUnloadExtensions(), is(empty()));
        assertThat(result.isNewerJavaVersionRequired(), is(false));
    }

    private AddOnCollection addOnCollection(AddOn... addOns) {
        AddOnCollection collection = new AddOnCollection(new File[0]);

        for (AddOn addOn : addOns) {
            collection.addAddOn(addOn);
        }
        return collection;
    }

    private AddOn createAvailableAddOn(String fileName, String version, Consumer<StringBuilder> manifestConsumer) throws Exception {
        AddOn addOn = new AddOn(createAddOnFile(fileName, version, manifestConsumer));
        addOn.setInstallationStatus(AddOn.InstallationStatus.AVAILABLE);

        return addOn;
    }

    private Path createAddOnFile(String fileName, String version, Consumer<StringBuilder> manifestConsumer) throws IOException {
        Path file = tempDir.resolve(fileName);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(AddOn.MANIFEST_FILE_NAME));

            StringBuilder manifest = new StringBuilder(256);
            manifest.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .append("<addon>")
                    .append("<name>")
                    .append(fileName)
                    .append("</name>")
                    .append("<status>release</status>")
                    .append("<version>")
                    .append(version)
                    .append("</version>");

            if (manifestConsumer != null) {
                manifestConsumer.accept(manifest);
            }

            manifest.append("</addon>");

            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
        }

        return file;
    }

    private static void appendDependency(StringBuilder manifest, String id, String version) {
        manifest.append("<dependencies>")
                .append("<addons>")
                .append("<addon>")
                .append("<id>")
                .append(id)
                .append("</id>");

        if (version != null && !version.isEmpty()) {
            manifest.append("<version>").append(version).append("</version>");
        }

        manifest.append("</addon>")
                .append("</addons>")
                .append("</dependencies>");
    }

    private static void appendExtensionWithDependency(StringBuilder manifest, String classname, String dependencyId, String version) {
        manifest.append("<extensions>")
                .append("<extension>")
                .append("<classname>")
                .append(classname)
                .append("</classname>")
                .append("<dependencies>")
                .append("<addons>")
                .append("<addon>")
                .append("<id>")
                .append(dependencyId)
                .append("</id>");

        if (version != null && !version.isEmpty()) {
            manifest.append("<version>").append(version).append("</version>");
        }

        manifest.append("</addon>")
                .append("</addons>")
                .append("</dependencies>")
                .append("</extension>")
                .append("</extensions>");
    }
}