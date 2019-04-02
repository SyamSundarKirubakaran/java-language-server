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

    public CompilationUnitTree root(URI uri) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(uri)) {
                return root;
            }
        }
        // Somehow, uri was not in batch
        var names = new StringJoiner(", ");
        for (var r : roots) {
            names.add(Parser.fileName(r.getSourceFile().toUri()));
        }
        throw new RuntimeException("File " + uri + " isn't in batch " + names);
    }

    private String contents(CompilationUnitTree root) {
        try {
            return root.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Element> element(URI uri, int line, int character) {
        var root = root(uri);
        var path = CompileFocus.findPath(borrow.task, root, line, character);
        var el = trees.getElement(path);
        return Optional.ofNullable(el);
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

    public SourcePositions sourcePositions() {
        return trees.getSourcePositions();
    }

    public LineMap lineMap(URI uri) {
        return root(uri).getLineMap();
    }

    public List<? extends ImportTree> imports(URI uri) {
        return root(uri).getImports();
    }

    private List<Element> overrides(ExecutableElement method) {
        var elements = borrow.task.getElements();
        var types = borrow.task.getTypes();
        var results = new ArrayList<Element>();
        var enclosingClass = (TypeElement) method.getEnclosingElement();
        var enclosingType = enclosingClass.asType();
        for (var superClass : types.directSupertypes(enclosingType)) {
            var e = (TypeElement) types.asElement(superClass);
            for (var other : e.getEnclosedElements()) {
                if (!(other instanceof ExecutableElement)) continue;
                if (elements.overrides(method, (ExecutableElement) other, enclosingClass)) {
                    results.add(other);
                }
            }
        }
        return results;
    }

    private boolean hasOverrideAnnotation(ExecutableElement method) {
        for (var ann : method.getAnnotationMirrors()) {
            var type = ann.getAnnotationType();
            var el = type.asElement();
            var name = el.toString();
            if (name.equals("java.lang.Override")) {
                return true;
            }
        }
        return false;
    }

    /** Find methods that override a method from a superclass but don't have an @Override annotation. */
    public List<TreePath> needsOverrideAnnotation(URI uri) {
        LOG.info(String.format("Looking for methods that need an @Override annotation in %s ...", uri.getPath()));

        var root = root(uri);
        var results = new ArrayList<TreePath>();
        class FindMissingOverride extends TreePathScanner<Void, Void> {
            @Override
            public Void visitMethod(MethodTree t, Void __) {
                var method = (ExecutableElement) trees.getElement(getCurrentPath());
                var supers = overrides(method);
                if (!supers.isEmpty() && !hasOverrideAnnotation(method)) {
                    var overridesMethod = supers.get(0);
                    var overridesClass = overridesMethod.getEnclosingElement();
                    LOG.info(
                            String.format(
                                    "...`%s` has no @Override annotation but overrides `%s.%s`",
                                    method, overridesClass, overridesMethod));
                    results.add(getCurrentPath());
                }
                return super.visitMethod(t, null);
            }
        }
        new FindMissingOverride().scan(root, null);
        return results;
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    public List<String> fixImports(URI uri) {
        var root = root(uri);
        var contents = contents(root);
        // Check diagnostics for missing imports
        var unresolved = new HashSet<String>();
        for (var d : parent.diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location") && d.getSource().toUri().equals(uri)) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                var id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                } else LOG.warning(id + " doesn't look like a class");
            } else if (d.getMessage(null).contains("cannot find to")) {
                var lines = d.getMessage(null).split("\n");
                var firstLine = lines.length > 0 ? lines[0] : "";
                LOG.warning(String.format("%s %s doesn't look like to-not-found", d.getCode(), firstLine));
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        // TODO cache parsed imports on a per-file basis
        var sourcePathImports = Parser.existingImports(FileStore.all());
        var classes = new HashSet<String>();
        classes.addAll(parent.jdkClasses);
        classes.addAll(parent.classPathClasses);
        var fixes = Parser.resolveSymbols(unresolved, sourcePathImports, classes);
        // Figure out which existing imports are actually used
        var trees = Trees.instance(borrow.task);
        var references = new HashSet<String>();
        class FindUsedImports extends TreePathScanner<Void, Void> {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                var e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement) {
                    var t = (TypeElement) e;
                    var qualifiedName = t.getQualifiedName().toString();
                    var lastDot = qualifiedName.lastIndexOf('.');
                    var packageName = lastDot == -1 ? "" : qualifiedName.substring(0, lastDot);
                    var thisPackage = Objects.toString(root.getPackageName(), "");
                    // java.lang.* and current package are imported by default
                    if (!packageName.equals("java.lang")
                            && !packageName.equals(thisPackage)
                            && !packageName.equals("")) {
                        references.add(qualifiedName);
                    }
                }
                return null;
            }
        }
        new FindUsedImports().scan(root, null);
        // Take the intersection of existing imports ^ existing identifiers
        var qualifiedNames = new HashSet<String>();
        for (var i : root.getImports()) {
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                var packageName = Parser.mostName(imported);
                var isUsed = references.stream().anyMatch(r -> r.startsWith(packageName));
                if (isUsed) qualifiedNames.add(imported);
                else LOG.warning("There are no references to package " + imported);
            } else {
                if (references.contains(imported)) qualifiedNames.add(imported);
                else LOG.warning("There are no references to class " + imported);
            }
        }
        // Add qualified names from fixes
        qualifiedNames.addAll(fixes.values());
        // Sort in alphabetical order
        var sorted = new ArrayList<String>();
        sorted.addAll(qualifiedNames);
        Collections.sort(sorted);
        return sorted;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
