package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.*;
import javax.tools.*;
import org.javacs.lsp.Range;

public class CompileBatch implements AutoCloseable {
    private final JavaCompilerService parent;
    private final ReportProgress progress;
    private final TaskPool.Borrow borrow;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final List<CompilationUnitTree> roots;

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files, ReportProgress progress) {
        this.parent = parent;
        this.progress = progress;
        this.borrow = batchTask(parent, files);
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<CompilationUnitTree>();
        // Print timing information for optimization
        var profiler = new Profiler();
        borrow.task.addTaskListener(profiler);
        // Show progress message through the UI
        class CountFiles implements TaskListener {
            Set<URI> parse = new HashSet<>(), enter = new HashSet<>(), analyze = new HashSet<>();

            void inc(String message) {
                var n = parse.size() + enter.size() + analyze.size();
                var total = files.size() * 3;
                progress.progress(message, n, total);
            }

            @Override
            public void started(TaskEvent e) {
                var uri = e.getSourceFile().toUri();
                switch (e.getKind()) {
                    case PARSE:
                        if (parse.add(uri)) inc("Parse sources");
                        break;
                    case ENTER:
                        if (enter.add(uri)) inc("Enter symbols");
                        break;
                    case ANALYZE:
                        var name = Parser.fileName(uri);
                        if (analyze.add(uri)) inc("Analyze " + name);
                        break;
                }
            }
        }
        borrow.task.addTaskListener(new CountFiles());
        // Compile all roots
        try {
            for (var t : borrow.task.parse()) roots.add(t);
            // The results of borrow.task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            borrow.task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
    }

    @Override
    public void close() {
        borrow.close();
    }

    static TaskPool.Borrow batchTask(JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diags.clear();
        return parent.compiler.getTask(
                null,
                parent.fileManager,
                parent.diags::add,
                JavaCompilerService.options(parent.classPath),
                Collections.emptyList(),
                sources);
    }

    public Optional<Element> element(URI uri, int line, int character) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(uri)) {
                var path = CompileFocus.findPath(borrow.task, root, line, character);
                var el = trees.getElement(path);
                return Optional.ofNullable(el);
            }
        }
        // Somehow, uri was not in batch
        var names = new StringJoiner(", ");
        for (var r : roots) {
            names.add(Parser.fileName(r.getSourceFile().toUri()));
        }
        throw new RuntimeException("File " + uri + " isn't in batch " + names);
    }

    public Optional<List<TreePath>> definitions(Element el) {
        LOG.info(String.format("Search for definitions of `%s` in %d files...", el, roots.size()));

        if (el.asType().getKind() == TypeKind.ERROR) {
            LOG.info(String.format("...`%s` is an error type, giving up", el.asType()));
            return Optional.empty();
        }

        var refs = new ArrayList<TreePath>();
        class FindDefinitions extends TreePathScanner<Void, Void> {
            boolean sameSymbol(Element found) {
                return el.equals(found);
            }

            boolean isSubMethod(Element found) {
                if (!(el instanceof ExecutableElement)) return false;
                if (!(found instanceof ExecutableElement)) return false;
                var superMethod = (ExecutableElement) el;
                var subMethod = (ExecutableElement) found;
                var subType = (TypeElement) subMethod.getEnclosingElement();
                // TODO need to check if class is compatible as well
                if (elements.overrides(subMethod, superMethod, subType)) {
                    // LOG.info(String.format("...`%s.%s` overrides `%s`", subType, subMethod, superMethod));
                    return true;
                }
                return false;
            }

            void check(TreePath from) {
                var found = trees.getElement(from);
                var match = sameSymbol(found) || isSubMethod(found);
                if (match) refs.add(from);
            }

            @Override
            public Void visitClass(ClassTree t, Void __) {
                check(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                check(getCurrentPath());
                return super.visitMethod(t, null);
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                check(getCurrentPath());
                return super.visitVariable(t, null);
            }
        }
        var finder = new FindDefinitions();
        for (var r : roots) {
            finder.scan(r, null);
        }
        return Optional.of(refs);
    }

    public Optional<List<TreePath>> references(Element to) {
        LOG.info(String.format("Search for references to %s...", to));

        // If to is an error, we won't be able to find anything
        if (to.asType().getKind() == TypeKind.ERROR) {
            LOG.info(String.format("...`%s` is an error type, giving up", to.asType()));
            return Optional.empty();
        }

        // Otherwise, scan roots for references
        List<TreePath> list = new ArrayList<TreePath>();
        var map = Map.of(to, list);
        var finder = new FindReferences(borrow.task);
        for (var r : roots) {
            finder.scan(r, map);
        }
        LOG.info(String.format("...found %d references", list.size()));
        return Optional.of(list);
    }

    /**
     * Find all elements in `file` that get turned into code-lenses. This needs to match the result of
     * `ParseFile#declarations`
     */
    public List<Element> declarations(URI file) {
        for (var r : roots) {
            if (!r.getSourceFile().toUri().equals(file)) continue;
            var paths = ParseFile.declarations(r);
            var els = new ArrayList<Element>();
            for (var p : paths) {
                var e = trees.getElement(p);
                assert e != null;
                els.add(e);
            }
            return els;
        }

        // Couldn't find file! Throw an error.
        var message = new StringJoiner(", ");
        for (var r : roots) {
            message.add(Parser.fileName(r.getSourceFile().toUri()));
        }
        throw new RuntimeException(file + " is not in " + message);
    }

    public Index index(URI from, List<Element> declarations) {
        for (var r : roots) {
            if (r.getSourceFile().toUri().equals(from)) {
                return new Index(borrow.task, r, parent.diags, declarations);
            }
        }
        throw new RuntimeException(from + " is not in compiled batch");
    }

    public Optional<Range> range(TreePath path) {
        var uri = path.getCompilationUnit().getSourceFile().toUri();
        var contents = FileStore.contents(uri);
        return ParseFile.range(borrow.task, contents, path);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
