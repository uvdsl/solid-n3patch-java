package edu.kit.aifb.solid.n3patch.guards;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.update.UpdateRequest;

public class N3WhereMatchGuard {

    public Binding check(UpdateRequest patch, Dataset dataset) {

        UpdateModify update = (UpdateModify) patch.getOperations().get(0);
        Element where = update.getWherePattern();

        // if no WHERE clause, we can return early
        if (where instanceof ElementGroup group
                && group.getElements().isEmpty()) {
            return BindingFactory.empty();
        }

        Query query = new Query();
        query.setQuerySelectType();
        query.setQueryResultStar(true);
        query.setDistinct(true);
        query.setLimit(2); // we need to see if a second match exists, hence LIMIT 2
        query.setQueryPattern(where);

        var target = dataset.getDefaultModel();

        // CHECK 1: does exactly one mapping per variable exist?
        try (QueryExecution execution = QueryExecutionFactory.create(query, target)) {
            
            var results = execution.execSelect();
            // if no mapping -> 409
            if (!results.hasNext()) {
                throw new N3ConflictException("No where-match");
            }

            Binding binding = results.nextBinding();
            // if multiple mappings -> 409
            if (results.hasNext()) {
                throw new N3ConflictException("Ambiguous where-match");
            }

            // one binding -> ok
            return binding;
        }
    }
}
