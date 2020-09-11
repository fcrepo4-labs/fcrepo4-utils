/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.upgrade.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.annotations.VisibleForTesting;
import edu.wisc.library.ocfl.api.MutableOcflRepository;
import edu.wisc.library.ocfl.api.OcflConfig;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.OcflRepositoryBuilder;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedTruncatedNTupleConfig;
import edu.wisc.library.ocfl.core.path.mapper.LogicalPathMappers;
import edu.wisc.library.ocfl.core.storage.filesystem.FileSystemOcflStorage;
import org.apache.commons.lang3.SystemUtils;
import org.fcrepo.storage.ocfl.CommitType;
import org.fcrepo.storage.ocfl.DefaultOcflObjectSessionFactory;
import org.fcrepo.storage.ocfl.OcflObjectSessionFactory;
import org.fcrepo.upgrade.utils.f6.MigrationTaskManager;
import org.fcrepo.upgrade.utils.f6.ResourceMigrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static java.lang.String.format;

/**
 * A factory class for creating Upgrade managers based on source and target versions.
 * @author  dbernstein
 */
public class UpgradeManagerFactory {

    public static UpgradeManager create(final Config config) {
        if (config.getSourceVersion().equals(FedoraVersion.V_4_7_5) &&
            config.getTargetVersion().equals(FedoraVersion.V_5)) {
            return new F47ToF5UpgradeManager(config);
        } else if (config.getSourceVersion().equals(FedoraVersion.V_5) &&
                   config.getTargetVersion().equals(FedoraVersion.V_6)) {
            return new F5ToF6UpgradeManager(config, createF6MigrationTaskManager(config));
        } else {
            throw new IllegalArgumentException(format("The migration path from %s to %s is not supported.",
                                                      config.getSourceVersion().getStringValue(),
                                                      config.getTargetVersion().getStringValue()));
        }
    }

    private static MigrationTaskManager createF6MigrationTaskManager(final Config config) {
        final var executor = Executors.newFixedThreadPool(config.getThreads());
        final var migrator = new ResourceMigrator(config, createOcflObjectSessionFactory(config));
        return new MigrationTaskManager(executor, migrator);
    }

    @VisibleForTesting
    public static OcflObjectSessionFactory createOcflObjectSessionFactory(final Config config) {
        try {
            final var output = config.getOutputDir().toPath();
            final var ocflRoot = Files.createDirectories(output.resolve("ocfl-root"));
            final var work = Files.createDirectories(output.resolve("ocfl-temp"));
            final var staging = Files.createDirectories(output.resolve("staging"));

            final var objectMapper = new ObjectMapper()
                    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

            return new DefaultOcflObjectSessionFactory(createOcflRepo(config, ocflRoot, work),
                    staging,
                    objectMapper,
                    CommitType.NEW_VERSION,
                    "Generated by Fedora 4/5 to Fedora 6 migration",
                    config.getFedoraUser(),
                    config.getFedoraUserAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MutableOcflRepository createOcflRepo(final Config config,
                                                        final Path storageRoot,
                                                        final Path workDir) {
        final var logicalPathMapper = SystemUtils.IS_OS_WINDOWS ?
                LogicalPathMappers.percentEncodingWindowsMapper() : LogicalPathMappers.percentEncodingLinuxMapper();

        final var digestAlgorithm = DigestAlgorithm.fromOcflName(config.getDigestAlgorithm());

        return new OcflRepositoryBuilder()
                .layoutConfig(new HashedTruncatedNTupleConfig())
                .ocflConfig(new OcflConfig()
                        .setDefaultDigestAlgorithm(digestAlgorithm))
                .logicalPathMapper(logicalPathMapper)
                .storage((FileSystemOcflStorage.builder().repositoryRoot(storageRoot).build()))
                .workDir(workDir)
                .buildMutable();
    }

}
