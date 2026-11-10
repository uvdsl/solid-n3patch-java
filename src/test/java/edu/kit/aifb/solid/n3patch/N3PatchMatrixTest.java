package edu.kit.aifb.solid.n3patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.tdb2.TDB2Factory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import edu.kit.aifb.solid.n3patch.guards.N3ConflictException;
import edu.kit.aifb.solid.n3patch.parsers.N3BadPatchException;

public class N3PatchMatrixTest {

    private static final String TARGET = "http://example.org/alice";

    private static final String PREFIXES = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#>.
            @prefix ex: <http://example.org/vocab#>.
            """;

    private static final String DATA_PREFIX = """
            @prefix ex: <http://example.org/vocab#>.
            """;

    private static final String FIXTURE = DATA_PREFIX + """
            <http://example.org/alice#me>
                ex:name "Alice";
                ex:knows <#bob>, <#carol>.
            """;

    private static final String RENAMED = DATA_PREFIX + """
            <http://example.org/alice#me>
                ex:name "Alicia";
                ex:knows <#bob>, <#carol>.
            """;

    private static final String WITHOUT_BOB = DATA_PREFIX + """
            <http://example.org/alice#me>
                ex:name "Alice";
                ex:knows <#carol>.
            """;

    private static final String WITH_AGE = DATA_PREFIX + """
            <http://example.org/alice#me>
                ex:name "Alice";
                ex:knows <#bob>, <#carol>;
                ex:age 30.
            """;

    private static final String WITH_NOTE = DATA_PREFIX + """
            <http://example.org/alice#me>
                ex:name "Alice";
                ex:knows <#bob>, <#carol>;
                ex:note "a {curly} ?question".
            """;

    private static final String CONSTANT_RENAME = patch("""
            solid:where {
                <#me> ex:name ?oldName.
            };
            solid:deletes {
                <#me> ex:name "Alice".
            };
            solid:inserts {
                <#me> ex:name "Alicia".
            }
            """);

    @TempDir
    Path temporaryDirectory;

    private record Scenario(
            String targetTurtle,
            String patchN3,
            Class<? extends RuntimeException> expectedException,
            String expectedPostState) {
    }

    private record Row(String id, List<Scenario> scenarios) {
    }

    @TestFactory
    Stream<DynamicTest> matrix() {
        Stream<DynamicTest> regularTests = rows().stream()
                .map(row -> dynamicTest(row.id(), () -> {
                    for (Scenario scenario : row.scenarios()) {
                        runScenario(row.id(), scenario);
                    }
                }));

        return Stream.concat(
                regularTests,
                Stream.of(dynamicTest("D-concurrent", this::runConcurrent)));
    }

    private static List<Row> rows() {
        return List.of(
                row(
                        "A-no-type",
                        FIXTURE,
                        PREFIXES + """
                                <#patch>
                                    solid:where {
                                        <#me> ex:name ?oldName.
                                    };
                                    solid:deletes {
                                        <#me> ex:name "Alice".
                                    };
                                    solid:inserts {
                                        <#me> ex:name "Alicia".
                                    }.
                                """,
                        N3BadPatchException.class,
                        null),

                row(
                        "A-two-patches",
                        FIXTURE,
                        PREFIXES + """
                                <#first> a solid:InsertDeletePatch;
                                    solid:inserts {
                                        <#me> ex:age 30.
                                    }.

                                <#second> a solid:InsertDeletePatch;
                                    solid:inserts {
                                        <#me> ex:age 31.
                                    }.
                                """,
                        N3BadPatchException.class,
                        null),

                row(
                        "A-duplicate-predicate",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:name ?name.
                                };
                                solid:deletes {
                                    <#me> ex:name ?name.
                                };
                                solid:deletes {
                                    <#me> ex:knows <#bob>.
                                }
                                """),
                        N3BadPatchException.class,
                        null),

                row(
                        "A-scope",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:name ?name.
                                };
                                solid:deletes {
                                    <#me> ex:age ?age.
                                }
                                """),
                        N3BadPatchException.class,
                        null),

                row(
                        "A-bnode-deletes",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:knows ?person.
                                };
                                solid:deletes {
                                    <#me> ex:knows _:person.
                                }
                                """),
                        N3BadPatchException.class,
                        null),

                row(
                        "A-bnode-inserts",
                        FIXTURE,
                        patch("""
                                solid:where {};
                                solid:inserts {
                                    <#me> ex:knows _:person.
                                }
                                """),
                        N3BadPatchException.class,
                        null),

                row(
                        "A-nested-formula",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:knows {
                                        <#me> ex:name ?name.
                                    }.
                                }
                                """),
                        N3BadPatchException.class,
                        null),

                row(
                        "A-garbage",
                        FIXTURE,
                        "this is not an RDF patch",
                        N3BadPatchException.class,
                        null),

                row(
                        "B-no-match",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:age ?age.
                                };
                                solid:deletes {
                                    <#me> ex:age ?age.
                                };
                                solid:inserts {
                                    <#me> ex:age 30.
                                }
                                """),
                        N3ConflictException.class,
                        null),

                row(
                        "B-ambiguous",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:knows ?person.
                                };
                                solid:deletes {
                                    <#me> ex:knows ?person.
                                }
                                """),
                        N3ConflictException.class,
                        null),

                row(
                        "B-delete-absent",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:name ?name.
                                };
                                solid:deletes {
                                    <#me> ex:name ?name.
                                    <#me> ex:age 30.
                                };
                                solid:inserts {
                                    <#me> ex:name "Alicia".
                                }
                                """),
                        N3ConflictException.class,
                        null),

                row(
                        "B-empty-where",
                        FIXTURE,
                        patch("""
                                solid:where {};
                                solid:deletes {
                                    <#me> ex:knows <#bob>.
                                }
                                """),
                        null,
                        WITHOUT_BOB),

                row(
                        "C-rename",
                        FIXTURE,
                        patch("""
                                solid:where {
                                    <#me> ex:name ?oldName.
                                };
                                solid:deletes {
                                    <#me> ex:name ?oldName.
                                };
                                solid:inserts {
                                    <#me> ex:name "Alicia".
                                }
                                """),
                        null,
                        RENAMED),

                new Row(
                        "C-pure-insert / C-pure-delete",
                        List.of(
                                new Scenario(
                                        FIXTURE,
                                        patch("""
                                                solid:where {};
                                                solid:inserts {
                                                    <#me> ex:age 30.
                                                }
                                                """),
                                        null,
                                        WITH_AGE),

                                new Scenario(
                                        FIXTURE,
                                        patch("""
                                                solid:where {};
                                                solid:deletes {
                                                    <#me> ex:knows <#bob>.
                                                }
                                                """),
                                        null,
                                        WITHOUT_BOB))),

                row(
                        "C-empty-target",
                        null,
                        patch("""
                                solid:inserts {
                                    <#me> ex:name "Alice".
                                }
                                """),
                        null,
                        DATA_PREFIX + """
                                <http://example.org/alice#me>
                                    ex:name "Alice".
                                """),

                row(
                        "C-relative-iri",
                        DATA_PREFIX + """
                                <http://example.org/alice#me>
                                    ex:name "Alice".
                                """,
                        CONSTANT_RENAME,
                        null,
                        DATA_PREFIX + """
                                <http://example.org/alice#me>
                                    ex:name "Alicia".
                                """),

                row(
                        "C-brace-literal",
                        FIXTURE,
                        patch("""
                                solid:where {};
                                solid:inserts {
                                    <#me> ex:note "a {curly} ?question".
                                }
                                """),
                        null,
                        WITH_NOTE));
    }

    private static Row row(
            String id,
            String targetTurtle,
            String patchN3,
            Class<? extends RuntimeException> expectedException,
            String expectedPostState) {

        return new Row(
                id,
                List.of(new Scenario(
                        targetTurtle,
                        patchN3,
                        expectedException,
                        expectedPostState)));
    }

    private static String patch(String formulas) {
        return PREFIXES
                + "<#patch> a solid:InsertDeletePatch;\n"
                + formulas
                + ".\n";
    }

    private static void runScenario(String id, Scenario scenario) {
        Dataset dataset = DatasetFactory.createTxnMem();

        try {
            loadTarget(dataset, scenario.targetTurtle());

            N3PatchApplier applier = new N3PatchApplier();

            if (scenario.expectedException() == null) {
                applier.apply(scenario.patchN3(), TARGET, dataset);

                assertTarget(
                        dataset,
                        scenario.expectedPostState(),
                        id);
            } else {
                assertThrowsExactly(
                        scenario.expectedException(),
                        () -> applier.apply(
                                scenario.patchN3(), TARGET, dataset),
                        id);

                assertTarget(
                        dataset,
                        scenario.targetTurtle(),
                        id + ": target must remain unchanged");
            }
        } finally {
            dataset.close();
        }
    }

    private static void loadTarget(Dataset dataset, String turtle) {
        if (turtle == null) {
            return;
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            RDFParser.fromString(turtle)
                    .lang(Lang.TURTLE)
                    .base(TARGET)
                    .parse(dataset.getDefaultModel());

            dataset.commit();
        } catch (RuntimeException | Error e) {
            dataset.abort();
            throw e;
        } finally {
            dataset.end();
        }
    }

    private static void assertTarget(
            Dataset dataset, String expectedTurtle, String message) {

        Model expected = ModelFactory.createDefaultModel();

        try {
            if (expectedTurtle != null) {
                RDFParser.fromString(expectedTurtle)
                        .lang(Lang.TURTLE)
                        .base(TARGET)
                        .parse(expected);
            }

            dataset.begin(ReadWrite.READ);
            try {
                Model actual = dataset.getDefaultModel();

                assertTrue(
                        actual.isIsomorphicWith(expected),
                        message + ": RDF post-state mismatch");
            } finally {
                dataset.end();
            }
        } finally {
            expected.close();
        }
    }

    private void runConcurrent() throws Exception {
        Path directory = temporaryDirectory.resolve("tdb2");
        Dataset dataset = TDB2Factory.connectDataset(directory.toString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            loadTarget(dataset, FIXTURE);

            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "Timed out waiting to start");
                    }

                    try {
                        new N3PatchApplier().apply(
                                CONSTANT_RENAME, TARGET, dataset);
                        return true;
                    } catch (N3ConflictException e) {
                        return false;
                    }
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both workers must be ready");

            start.countDown();

            int applied = 0;
            int conflicts = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    applied++;
                } else {
                    conflicts++;
                }
            }

            assertEquals(1, applied, "Exactly one patch must apply");
            assertEquals(1, conflicts, "Exactly one patch must conflict");

            assertTarget(dataset, RENAMED, "D-concurrent");
        } finally {
            start.countDown();
            executor.shutdownNow();

            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } finally {
                dataset.close();
            }
        }
    }
}