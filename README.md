# Solid N3 Patch

This library implements applying an [Solid N3 Patch](https://solidproject.org/ED/protocol#n3-patch) in plain Java, (... _sips coffee_ ...).

Software using this code needs to
- provide the N3 Patch as a `String`
- provide the target resource URI for resolving relative IRIs
- provide an abortable transactional Apache Jena `Dataset`, such as
  `DatasetFactory.createTxnMem()` or a TDB2 dataset, with the resource’s triples
  in its default graph.

A plain `DatasetFactory.create()` is not supported by the [applier](src/main/java/org/solid/n3patch/N3PatchApplier.java).
The dataset must support transactions and rollback so both precondition checks
and execution remain atomic, and failures leave the resource unchanged.


## Why not just run a SPARQL update?

We almost do.

The [translator](src/main/java/org/solid/n3patch/parsers/N3PatchTranslator.java) turns the N3 patch into a self-contained SPARQL update. Before executing it, we check:
- the `solid:where` pattern matches **exactly one** distinct variable mapping
- every triple in the instantiated `solid:deletes` formula is present.

All checks and the update run inside **one write transaction**. 

## Usage

The [applier](src/main/java/org/solid/n3patch/N3PatchApplier.java) provides one operation:

```java
public void apply(String patchText, String targetUri, Dataset dataset)
```

It returns normally on success and throws:
- `N3BadPatchException` for invalid patches -- corresponds to **422**
- `N3ConflictException` for failed preconditions -- corresponds to **409**.

Other runtime failures propagate after aborting the transaction.

HTTP responses, access control, ETags, and content negotiation belong to the calling application. This library does not implement them.

Want to run an example? Maybe something like:

```java
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;

import edu.kit.aifb.solid.n3patch.N3PatchApplier;
import edu.kit.aifb.solid.n3patch.guards.N3ConflictException;
import edu.kit.aifb.solid.n3patch.parsers.N3BadPatchException;

public class App {

    public static void main(String[] args) {
        String resource = "http://example.org/alice";

        String document = """
                @prefix ex: <http://example.org/vocab#>.
                <#me> ex:name "Alice".
                """;

        String patch = """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                @prefix ex: <http://example.org/vocab#>.

                _:rename a solid:InsertDeletePatch;
                    solid:where {
                        <#me> ex:name ?name.
                    };
                    solid:deletes {
                        <#me> ex:name "Alice".
                    };
                    solid:inserts {
                        <#me> ex:name "Alicia".
                    }.
                """;

        // Required: the patch applier needs transactions and rollback.
        Dataset dataset = DatasetFactory.createTxnMem();

        try {
            dataset.begin(ReadWrite.WRITE);
            try {
                RDFParser.fromString(document)
                        .lang(Lang.TURTLE)
                        .base(resource)
                        .parse(dataset.getDefaultModel());

                dataset.commit();
            } finally {
                dataset.end();
            }

            // The applier owns its transaction. Do not open one around this call.
            N3PatchApplier applier = new N3PatchApplier();
            applier.apply(patch, resource, dataset);
            System.out.println("Patch applied.");

            // The exact prior triple is gone: replay must conflict.
            try {
                applier.apply(patch, resource, dataset);
            } catch (N3ConflictException e) {
                System.out.println("Replay rejected: " + e.getMessage());
            }

            dataset.begin(ReadWrite.READ);
            try {
                dataset.getDefaultModel().write(System.out, "TURTLE");
            } finally {
                dataset.end();
            }
        } catch (N3BadPatchException e) {
            System.err.println("Invalid patch: " + e.getMessage());
        } catch (N3ConflictException e) {
            System.err.println("Patch conflict: " + e.getMessage());
        } finally {
            dataset.close();
        }
    }
}
```

By the way, the delete formula in this example cites `"Alice"` **as a constant**. That is intentional for the sake of the example:
The WHERE pattern binds `?name` to the current name. The delete formula independently requires the exact triple `<#me> ex:name "Alice"` to exist.

On the first application of the patch, both checks pass: the WHERE clause produces one mapping for `?name`, and the triple to delete is indeed is present. The patch replaces `"Alice"` with `"Alicia"`.
Replaying the same patch throws **`Conflict` (409)**: WHERE still produces one mapping for `?name`, now with `?name = "Alicia"`, but the required delete triple containing `"Alice"` is gone.
If you instead delete `?name`, the delete formula uses the value mapped to `?name` in the WHERE clause. On replay, that value is `"Alicia"`, which is present, so the patch can succeed again.

You can also add a second, different name to the graph.
The two names produce two distinct variable mappings. That throws **`Conflict` (409)** because the match is ambiguous. If no name exists, WHERE produces zero mappings and also throws **`Conflict` (409)**. Neither case changes the graph.

These are precondition failures, not invalid patches. **`BadPatch` (422)** is thrown for malformed or structurally invalid input--for example, an undeclared variable in deletes/inserts, duplicate formula predicates, or forbidden blank nodes in deletes/inserts.

If you omit WHERE and use only concrete delete/insert triples, the delete-presence check still protects against replay. However, additional name values do not cause ambiguity: the patch removes exactly `"Alice"`, inserts `"Alicia"`, and leaves other names untouched.

## The SPARQL update

You can use the translator independently:

```java
import org.apache.jena.update.UpdateRequest;
import org.solid.n3patch.N3PatchTranslator;

UpdateRequest update =
        new N3PatchTranslator().translate(patchText, resource);

System.out.println(update);
```

However, executing that update directly gives you **SPARQL Update semantics**, not the complete N3 Patch contract. The [WHERE guard](src/main/java/org/solid/n3patch/guards/N3WhereMatchGuard.java) and [delete-presence guard](src/main/java/org/solid/n3patch/guards/N3DeletePresenceGuard.java) supply the missing precondition checks.

Use the applier to keep both guards and execution in the same transaction.

## Tests?

Sure, after cloning this repository, with Maven:

```bash
mvn test
```

Or build the library and run its tests:

```bash
mvn package
```

Find the test coverage report at `target/site/jacoco/index.html`.

The test matrix covers invalid patches, missing or ambiguous matches, absent delete triples, successful updates, relative IRIs, braces inside literals, and concurrent updates using TDB2.

## Dependencies

We rely on **Java 17** and [Apache Jena](https://jena.apache.org/) **4.10.0**:
- `jena-core` for handling RDF
- `jena-arq` for SPARQL parsing and execution.

Tests use **JUnit 5** and `jena-tdb2` for the concurrency test.
