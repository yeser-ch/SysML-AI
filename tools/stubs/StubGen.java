/*
 * Erzeugt Signatur-Stubs der CATIA-Programmierschnittstelle, gegen die das Plugin auf GitHub
 * (ohne CATIA) kompiliert werden kann. Die Stubs enthalten nur Signaturen (Klassen, Methoden,
 * Felder, Konstanten), keinen Programmcode von CATIA.
 *
 * Aufruf (startet man normalerweise über tools/update-catia-stubs.bat):
 *   java tools/stubs/StubGen.java <CATIA-Ordner> <src-Ordner> <Ausgabeordner> <Arbeitsordner>
 *
 * Ablauf
 *   1. Plugin-Quellcode gegen die echten CATIA-Bibliotheken kompilieren.
 *   2. Aus den .class-Dateien alle verwendeten Fremdklassen ermitteln.
 *   3. Für diese Klassen und ihre Oberklassen Stubs mit allen public/protected-Elementen schreiben,
 *      für Typen, die nur in Signaturen vorkommen, leere Stubs mit korrekter Vererbung.
 *   4. Stubs kompilieren, Plugin gegen die Stubs kompilieren und die .class-Dateien
 *      mit Schritt 1 vergleichen. Sind sie byte-gleich, ist der Build auf GitHub identisch.
 */
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;

public class StubGen {

    static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();
    static URLClassLoader loader;
    static final Set<String> own = new HashSet<>();                    // Klassen des Plugins
    static final LinkedHashMap<String, Class<?>> full = new LinkedHashMap<>();
    static final LinkedHashMap<String, Class<?>> shell = new LinkedHashMap<>();
    static final Set<String> forceAbstract = new HashSet<>();
    static final List<String> warnings = new ArrayList<>();
    static final Map<String, Map<String, Object>> constCache = new HashMap<>();

    public static void main(String[] a) throws Exception {
        if (a.length != 4) {
            System.err.println("Aufruf: java StubGen.java <CATIA-Ordner> <src-Ordner> <Ausgabeordner> <Arbeitsordner>");
            System.exit(1);
        }
        Path catia = Paths.get(a[0]), src = Paths.get(a[1]), out = Paths.get(a[2]), work = Paths.get(a[3]);
        deleteTree(work);
        Files.createDirectories(work);

        List<Path> jars = new ArrayList<>();
        for (String sub : new String[]{"lib", "plugins"}) {
            Path p = catia.resolve(sub);
            if (!Files.isDirectory(p)) continue;
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(f -> f.toString().endsWith(".jar"))
                 .filter(f -> !f.toString().matches("(?i).*(sysmlaiqa|schroedingersblock).*"))
                 .forEach(jars::add);
            }
        }
        if (jars.isEmpty()) fail("Keine .jar-Dateien in " + catia + "\\lib gefunden.");
        info(jars.size() + " CATIA-Bibliotheken");
        String realCp = jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

        List<File> sources;
        try (Stream<Path> s = Files.walk(src)) {
            sources = s.filter(f -> f.toString().endsWith(".java")).map(Path::toFile).sorted().collect(Collectors.toList());
        }
        info(sources.size() + " Quelldateien");

        // 1. gegen echte Bibliotheken kompilieren
        step("1/4  Plugin gegen die echten CATIA-Bibliotheken kompilieren");
        Path realClasses = work.resolve("real");
        Set<String> sourceRefs = compileAndCollect(sources, realCp, realClasses);

        // 2. verwendete Fremdklassen ermitteln
        step("2/4  Verwendete CATIA-Klassen ermitteln");
        List<Path> classFiles;
        try (Stream<Path> s = Files.walk(realClasses)) {
            classFiles = s.filter(f -> f.toString().endsWith(".class")).sorted().collect(Collectors.toList());
        }
        for (Path f : classFiles) {
            String rel = realClasses.relativize(f).toString().replace(File.separatorChar, '/');
            own.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
        }
        TreeSet<String> refs = new TreeSet<>();
        for (Path f : classFiles) refs.addAll(ClassFile.parse(Files.readAllBytes(f)).referencedClasses());
        refs.addAll(sourceRefs);   // auch Klassen, von denen nur Konstanten verwendet werden (stehen nicht im Bytecode)
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
        loader = new URLClassLoader(urls, PLATFORM);
        int seeds = 0;
        for (String r : refs) {
            if (own.contains(r) || isJdk(r)) continue;
            Class<?> c = load(r);
            if (c != null) { addFull(c); seeds++; }
        }
        info(seeds + " direkt verwendete Klassen, " + full.size() + " mit Elementen, " + shell.size() + " nur als Typ");

        // 3. Stubs schreiben und kompilieren (Klassen, denen Implementierungen fehlen, werden abstrakt)
        step("3/4  Stubs schreiben und kompilieren");
        Path stubSrc = out.resolve("src");
        Path stubClasses = work.resolve("stubs");
        boolean ok = false;
        for (int round = 0; round < 6 && !ok; round++) {
            deleteTree(stubSrc);
            List<File> stubFiles = writeStubs(stubSrc);
            DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
            ok = compile(stubFiles, null, stubClasses, false, diag);
            if (ok) break;
            int fixed = 0;
            for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                if ("compiler.err.does.not.override.abstract".equals(d.getCode())) {
                    String msg = d.getMessage(Locale.ROOT);
                    String cls = msg.substring(0, msg.indexOf(' ')).trim();
                    if (forceAbstract.add(cls)) fixed++;
                }
            }
            if (fixed == 0) {
                for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics())
                    if (d.getKind() == Diagnostic.Kind.ERROR) System.err.println(d);
                fail("Die Stubs ließen sich nicht kompilieren (Meldungen oben).");
            }
        }
        if (!ok) fail("Die Stubs ließen sich nicht kompilieren.");
        info("Stubs: " + (full.size() + shell.size()) + " Klassen");

        // 4. gegen Stubs kompilieren und vergleichen
        step("4/4  Plugin gegen die Stubs kompilieren und vergleichen");
        Path stubBuild = work.resolve("viastubs");
        if (!compile(sources, stubClasses.toString(), stubBuild, true))
            fail("Das Plugin lässt sich nicht gegen die Stubs kompilieren (Meldungen oben).");
        List<String> diffs = new ArrayList<>();
        for (Path f : classFiles) {
            Path g = stubBuild.resolve(realClasses.relativize(f));
            if (!Files.exists(g) || !Arrays.equals(Files.readAllBytes(f), Files.readAllBytes(g)))
                diffs.add(realClasses.relativize(f).toString());
        }
        String catiaName = catia.getFileName() == null ? catia.toString() : catia.getFileName().toString();
        List<String> meta = new ArrayList<>();
        meta.add("CATIA: " + catiaName);
        meta.add("Erzeugt: " + java.time.LocalDate.now());
        meta.add("Java: " + System.getProperty("java.version"));
        meta.add("Klassen mit Elementen: " + full.size());
        meta.add("Klassen nur als Typ: " + shell.size());
        meta.add("Byte-gleiche Klassen des Plugins: " + (classFiles.size() - diffs.size()) + " von " + classFiles.size());
        Files.write(out.resolve("SOURCE.txt"), meta, StandardCharsets.UTF_8);
        for (String w : warnings) System.out.println("   Hinweis: " + w);
        if (diffs.isEmpty()) {
            ok("Alle " + classFiles.size() + " Klassen des Plugins sind byte-gleich. Die Stubs passen.");
            System.exit(0);
        }
        System.out.println("   Unterschiede in " + diffs.size() + " Klassen:");
        for (String d : diffs) System.out.println("     " + d);
        System.out.println("   Das Plugin funktioniert damit voraussichtlich trotzdem (meist fehlen nur eingesetzte Konstanten),");
        System.out.println("   bitte die Ausgabe trotzdem an die Entwicklerin schicken.");
        System.exit(2);
    }

    // ───────────────────────────── Klassen sammeln ─────────────────────────────

    static boolean isJdk(String name) {
        try { Class.forName(name, false, PLATFORM); return true; } catch (Throwable t) { return false; }
    }

    static Class<?> load(String name) {
        try { return Class.forName(name, false, loader); }
        catch (Throwable t) { warnings.add("nicht ladbar: " + name + " (" + t + ")"); return null; }
    }

    static boolean skip(Class<?> c) {
        return c == null || c.isPrimitive() || c.getClassLoader() == null || c.getClassLoader() == PLATFORM
            || isJdk(c.getName()) || own.contains(c.getName());
    }

    static void addFull(Class<?> c) {
        while (c.isArray()) c = c.getComponentType();
        if (skip(c) || full.containsKey(c.getName())) return;
        if (c.isAnonymousClass() || c.isLocalClass()) { warnings.add("lokale Klasse übersprungen: " + c.getName()); return; }
        shell.remove(c.getName());
        full.put(c.getName(), c);
        if (c.getDeclaringClass() != null) addShell(c.getDeclaringClass());
        if (c.getSuperclass() != null) addFull(c.getSuperclass());
        for (Class<?> i : c.getInterfaces()) addFull(i);
        try {
            for (Type t : c.getGenericInterfaces()) visit(t);
            if (c.getGenericSuperclass() != null) visit(c.getGenericSuperclass());
            for (TypeVariable<?> v : c.getTypeParameters()) visit(v);
            for (Field f : fields(c)) visit(f.getGenericType());
            for (Method m : methods(c)) {
                visit(m.getGenericReturnType());
                for (Type t : m.getGenericParameterTypes()) visit(t);
                for (Type t : m.getGenericExceptionTypes()) visit(t);
                for (TypeVariable<?> v : m.getTypeParameters()) visit(v);
            }
            for (Constructor<?> k : ctors(c)) {
                for (Type t : k.getGenericParameterTypes()) visit(t);
                for (Class<?> t : k.getParameterTypes()) visit(t);
                for (Type t : k.getGenericExceptionTypes()) visit(t);
            }
        } catch (Throwable t) {
            warnings.add("Signaturen von " + c.getName() + " unvollständig (" + t + ")");
        }
    }

    static void addShell(Class<?> c) {
        while (c != null && c.isArray()) c = c.getComponentType();
        if (skip(c) || full.containsKey(c.getName()) || shell.containsKey(c.getName())) return;
        if (c.isAnonymousClass() || c.isLocalClass()) return;
        shell.put(c.getName(), c);
        if (c.getDeclaringClass() != null) addShell(c.getDeclaringClass());
        try {
            if (c.getSuperclass() != null) addShell(c.getSuperclass());
            for (Class<?> i : c.getInterfaces()) addShell(i);
            if (c.getGenericSuperclass() != null) visit(c.getGenericSuperclass());
            for (Type t : c.getGenericInterfaces()) visit(t);
            for (TypeVariable<?> v : c.getTypeParameters()) visit(v);
        } catch (Throwable t) {
            warnings.add("Vererbung von " + c.getName() + " unvollständig (" + t + ")");
        }
    }

    static final Set<TypeVariable<?>> seenVars = Collections.newSetFromMap(new IdentityHashMap<>());

    static void visit(Type t) {
        if (t instanceof Class) addShell((Class<?>) t);
        else if (t instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) t;
            visit(p.getRawType());
            if (p.getOwnerType() != null) visit(p.getOwnerType());
            for (Type x : p.getActualTypeArguments()) visit(x);
        } else if (t instanceof WildcardType) {
            for (Type x : ((WildcardType) t).getUpperBounds()) visit(x);
            for (Type x : ((WildcardType) t).getLowerBounds()) visit(x);
        } else if (t instanceof TypeVariable) {
            TypeVariable<?> v = (TypeVariable<?>) t;
            if (seenVars.add(v)) for (Type x : v.getBounds()) visit(x);
        } else if (t instanceof GenericArrayType) visit(((GenericArrayType) t).getGenericComponentType());
    }

    static boolean visible(int mod) { return Modifier.isPublic(mod) || Modifier.isProtected(mod); }

    static List<Field> fields(Class<?> c) {
        List<Field> r = new ArrayList<>();
        for (Field f : c.getDeclaredFields())
            if (!f.isSynthetic() && (visible(f.getModifiers()) || c.isInterface())) r.add(f);
        r.sort(Comparator.comparing(Field::getName));
        return r;
    }

    static List<Method> methods(Class<?> c) {
        List<Method> r = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge() || !visible(m.getModifiers())) continue;
            if (c.isEnum() && Modifier.isStatic(m.getModifiers())
                && (m.getName().equals("values") && m.getParameterCount() == 0
                    || m.getName().equals("valueOf") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class)) continue;
            r.add(m);
        }
        r.sort(Comparator.comparing(Method::toGenericString));
        return r;
    }

    static List<Constructor<?>> ctors(Class<?> c) {
        List<Constructor<?>> r = new ArrayList<>();
        if (c.isInterface() || c.isEnum()) return r;
        for (Constructor<?> k : c.getDeclaredConstructors())
            if (!k.isSynthetic() && visible(k.getModifiers())) r.add(k);
        r.sort(Comparator.comparing(Constructor::toGenericString));
        return r;
    }

    // ───────────────────────────── Quelltext schreiben ─────────────────────────────

    static List<File> writeStubs(Path dir) throws IOException {
        Map<String, Class<?>> all = new TreeMap<>();
        all.putAll(full);
        all.putAll(shell);
        List<File> files = new ArrayList<>();
        for (Class<?> c : all.values()) {
            if (c.getDeclaringClass() != null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("// Signatur-Stub, nur zum Kompilieren ohne CATIA. Erzeugt mit tools/update-catia-stubs.bat.\n");
            if (c.getPackage() != null && !c.getPackage().getName().isEmpty())
                sb.append("package ").append(c.getPackage().getName()).append(";\n\n");
            emitClass(sb, c, all, "");
            Path p = dir.resolve(c.getName().replace('.', File.separatorChar) + ".java");
            Files.createDirectories(p.getParent());
            Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
            files.add(p.toFile());
        }
        return files;
    }

    static boolean isInner(Class<?> c) {
        return c.getDeclaringClass() != null && !Modifier.isStatic(c.getModifiers()) && !c.isInterface() && !c.isEnum()
            && !c.isAnnotation() && !c.isRecord();
    }

    static void emitClass(StringBuilder sb, Class<?> c, Map<String, Class<?>> all, String ind) {
        boolean isFull = full.containsKey(c.getName());
        int mod = c.getModifiers();
        StringBuilder h = new StringBuilder(ind);
        if (Modifier.isPublic(mod)) h.append("public ");
        else if (Modifier.isProtected(mod)) h.append("protected ");
        else if (Modifier.isPrivate(mod)) h.append("private ");
        if (c.getDeclaringClass() != null && Modifier.isStatic(mod) && !c.isInterface() && !c.isEnum() && !c.isAnnotation())
            h.append("static ");
        boolean abs = false;
        if (c.isAnnotation()) h.append("@interface ");
        else if (c.isInterface()) h.append("interface ");
        else if (c.isEnum()) h.append("enum ");
        else {
            abs = Modifier.isAbstract(mod) || !isFull || forceAbstract.contains(c.getCanonicalName())
                  || forceAbstract.contains(c.getSimpleName()) || forceAbstract.contains(c.getName());
            if (abs) h.append("abstract ");
            else if (Modifier.isFinal(mod)) h.append("final ");
            h.append("class ");
        }
        h.append(c.getSimpleName()).append(typeParams(c.getTypeParameters()));
        if (!c.isInterface() && !c.isEnum() && !c.isRecord()) {
            Type sup = c.getGenericSuperclass();
            if (sup != null && sup != Object.class) h.append(" extends ").append(ts(sup));
        }
        if (!c.isAnnotation()) {
            Type[] itf = c.getGenericInterfaces();
            if (itf.length > 0) {
                h.append(c.isInterface() ? " extends " : " implements ");
                h.append(Arrays.stream(itf).map(StubGen::ts).collect(Collectors.joining(", ")));
            }
        }
        sb.append(h).append(" {\n");
        String in = ind + "    ";

        if (c.isEnum()) {
            List<String> consts = new ArrayList<>();
            // Hat eine Konstante einen eigenen Rumpf, ist die Enum-Klasse nicht final (sichtbar in InnerClasses)
            String body = Modifier.isFinal(mod) ? "" : " { }";
            for (Field f : c.getDeclaredFields()) if (f.isEnumConstant()) consts.add(f.getName() + body);
            sb.append(in).append(String.join(", ", consts)).append(";\n");
        }
        if (isFull && !c.isAnnotation()) {
            Map<String, Object> cv = constants(c);
            for (Field f : fields(c)) {
                if (f.isEnumConstant()) continue;
                int fm = f.getModifiers();
                StringBuilder s = new StringBuilder(in);
                if (!c.isInterface()) {
                    s.append(Modifier.isPublic(fm) ? "public " : "protected ");
                    if (Modifier.isStatic(fm)) s.append("static ");
                }
                Object val = cv.get(f.getName());
                boolean constant = Modifier.isStatic(fm) && Modifier.isFinal(fm) && val != null;
                if (constant) s.append(c.isInterface() ? "" : "final ");
                s.append(ts(f.getGenericType())).append(' ').append(f.getName());
                if (constant) s.append(" = ").append(literal(f.getType(), val));
                else if (c.isInterface()) s.append(" = ").append(nonConst(f.getType()));
                sb.append(s).append(";\n");
            }
            for (Constructor<?> k : ctors(c)) emitCtor(sb, c, k, in);
            if (!c.isInterface() && !c.isEnum() && ctors(c).isEmpty()) emitSyntheticCtor(sb, c, in);
            for (Method m : methods(c)) emitMethod(sb, c, m, in, abs);
        } else if (!c.isInterface() && !c.isEnum() && !c.isAnnotation()) {
            emitSyntheticCtor(sb, c, in);
        }
        for (Class<?> n : all.values())
            if (n.getDeclaringClass() == c) emitClass(sb, n, all, in);
        sb.append(ind).append("}\n");
    }

    static void emitMethod(StringBuilder sb, Class<?> c, Method m, String in, boolean classAbstract) {
        int mm = m.getModifiers();
        StringBuilder s = new StringBuilder(in);
        boolean body;
        if (c.isInterface()) {
            if (Modifier.isStatic(mm)) { s.append("static "); body = true; }
            else if (m.isDefault()) { s.append("default "); body = true; }
            else body = false;
        } else {
            s.append(Modifier.isPublic(mm) ? "public " : "protected ");
            if (Modifier.isStatic(mm)) s.append("static ");
            boolean abs = Modifier.isAbstract(mm) && !c.isEnum() && classAbstract;
            if (abs) s.append("abstract ");
            else if (Modifier.isFinal(mm)) s.append("final ");
            body = !abs;
        }
        String tp = typeParams(m.getTypeParameters());
        if (!tp.isEmpty()) s.append(tp).append(' ');
        s.append(ts(m.getGenericReturnType())).append(' ').append(m.getName()).append('(');
        s.append(params(m.getGenericParameterTypes(), m.isVarArgs())).append(')');
        s.append(throwsClause(m.getGenericExceptionTypes(), null));
        if (body) s.append(" { throw new java.lang.UnsupportedOperationException(); }");
        else s.append(';');
        sb.append(s).append('\n');
    }

    static Type[] ctorParams(Class<?> c, Constructor<?> k) {
        Type[] g = k.getGenericParameterTypes();
        if (isInner(c) && g.length == k.getParameterCount() && g.length > 0) g = Arrays.copyOfRange(g, 1, g.length);
        return g;
    }

    static Class<?>[] ctorRawParams(Class<?> c, Constructor<?> k) {
        Class<?>[] p = k.getParameterTypes();
        if (isInner(c) && p.length > 0) p = Arrays.copyOfRange(p, 1, p.length);
        return p;
    }

    static void emitCtor(StringBuilder sb, Class<?> c, Constructor<?> k, String in) {
        StringBuilder s = new StringBuilder(in);
        s.append(Modifier.isPublic(k.getModifiers()) ? "public " : "protected ");
        String tp = typeParams(k.getTypeParameters());
        if (!tp.isEmpty()) s.append(tp).append(' ');
        s.append(c.getSimpleName()).append('(').append(params(ctorParams(c, k), k.isVarArgs())).append(')');
        SuperCall sc = superCall(c);
        s.append(throwsClause(k.getGenericExceptionTypes(), sc.exceptions));
        s.append(" { ").append(sc.code).append("throw new java.lang.UnsupportedOperationException(); }");
        sb.append(s).append('\n');
    }

    static void emitSyntheticCtor(StringBuilder sb, Class<?> c, String in) {
        SuperCall sc = superCall(c);
        sb.append(in).append("protected ").append(c.getSimpleName()).append("()")
          .append(throwsClause(new Type[0], sc.exceptions)).append(" { ").append(sc.code)
          .append("throw new java.lang.UnsupportedOperationException(); }\n");
    }

    static final class SuperCall { String code = ""; List<Class<?>> exceptions = new ArrayList<>(); }

    /** Konstruktoraufruf der Oberklasse mit Platzhalterwerten. */
    static SuperCall superCall(Class<?> c) {
        SuperCall r = new SuperCall();
        Class<?> sup = c.getSuperclass();
        if (sup == null || sup == Object.class || c.isEnum() || c.isRecord()) return r;
        Class<?>[] best = null;
        Type[] bestGeneric = null;
        List<Class<?>> bestEx = null;
        boolean stub = full.containsKey(sup.getName()) || shell.containsKey(sup.getName());
        if (stub && (shell.containsKey(sup.getName()) || ctors(sup).isEmpty())) {
            best = new Class<?>[0]; bestGeneric = new Type[0];
            bestEx = new ArrayList<>(superCall(sup).exceptions);
        } else {
            List<Constructor<?>> ks = stub ? ctors(sup) : ctorsJdk(sup);
            for (Constructor<?> k : ks) {
                List<Class<?>> ex = checked(k.getExceptionTypes());
                Class<?>[] p = ctorRawParams(sup, k);
                if (best == null || ex.size() < bestEx.size() || ex.size() == bestEx.size() && p.length < best.length) {
                    best = p; bestGeneric = ctorParams(sup, k); bestEx = ex;
                }
            }
        }
        if (best == null) return r;
        List<String> args = new ArrayList<>();
        for (int i = 0; i < best.length; i++) {
            Type g = i < bestGeneric.length ? bestGeneric[i] : best[i];
            args.add(dummy(best[i], g instanceof TypeVariable || g instanceof GenericArrayType && containsVar(g)));
        }
        r.code = "super(" + String.join(", ", args) + "); ";
        r.exceptions = bestEx;
        return r;
    }

    static boolean containsVar(Type t) {
        if (t instanceof TypeVariable) return true;
        if (t instanceof GenericArrayType) return containsVar(((GenericArrayType) t).getGenericComponentType());
        return false;
    }

    static List<Constructor<?>> ctorsJdk(Class<?> c) {
        List<Constructor<?>> r = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) if (visible(k.getModifiers())) r.add(k);
        r.sort(Comparator.comparing(Constructor::toGenericString));
        return r;
    }

    static List<Class<?>> checked(Class<?>[] ex) {
        List<Class<?>> r = new ArrayList<>();
        for (Class<?> e : ex)
            if (!RuntimeException.class.isAssignableFrom(e) && !Error.class.isAssignableFrom(e)) r.add(e);
        return r;
    }

    static String dummy(Class<?> t, boolean typeVar) {
        if (t == int.class) return "0";
        if (t == long.class) return "0L";
        if (t == boolean.class) return "false";
        if (t == double.class) return "0d";
        if (t == float.class) return "0f";
        if (t == char.class) return "'\\0'";
        if (t == byte.class) return "(byte) 0";
        if (t == short.class) return "(short) 0";
        if (typeVar) return "null";
        return "(" + ts(t) + ") null";
    }

    static String throwsClause(Type[] declared, List<Class<?>> extra) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Type t : declared) s.add(ts(t));
        if (extra != null) for (Class<?> e : extra) s.add(ts(e));
        return s.isEmpty() ? "" : " throws " + String.join(", ", s);
    }

    static String params(Type[] ps, boolean varargs) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < ps.length; i++) {
            String t = ts(ps[i]);
            if (varargs && i == ps.length - 1 && t.endsWith("[]")) t = t.substring(0, t.length() - 2) + "...";
            r.add(t + " p" + i);
        }
        return String.join(", ", r);
    }

    static String typeParams(TypeVariable<?>[] vs) {
        if (vs.length == 0) return "";
        List<String> r = new ArrayList<>();
        for (TypeVariable<?> v : vs) {
            Type[] b = v.getBounds();
            if (b.length == 0 || b.length == 1 && b[0] == Object.class) r.add(v.getName());
            else r.add(v.getName() + " extends " + Arrays.stream(b).map(StubGen::ts).collect(Collectors.joining(" & ")));
        }
        return "<" + String.join(", ", r) + ">";
    }

    static String ts(Type t) {
        if (t instanceof Class) {
            Class<?> c = (Class<?>) t;
            if (c.isArray()) return ts(c.getComponentType()) + "[]";
            if (c.isPrimitive()) return c.getName();
            String n = c.getCanonicalName();
            return n != null ? n : c.getName().replace('$', '.');
        }
        if (t instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) t;
            Class<?> raw = (Class<?>) p.getRawType();
            String args = Arrays.stream(p.getActualTypeArguments()).map(StubGen::ts).collect(Collectors.joining(", "));
            String base = p.getOwnerType() instanceof ParameterizedType
                ? ts(p.getOwnerType()) + "." + raw.getSimpleName() : ts(raw);
            return base + (args.isEmpty() ? "" : "<" + args + ">");
        }
        if (t instanceof WildcardType) {
            WildcardType w = (WildcardType) t;
            if (w.getLowerBounds().length > 0) return "? super " + ts(w.getLowerBounds()[0]);
            Type[] u = w.getUpperBounds();
            if (u.length == 0 || u[0] == Object.class) return "?";
            return "? extends " + ts(u[0]);
        }
        if (t instanceof TypeVariable) return ((TypeVariable<?>) t).getName();
        if (t instanceof GenericArrayType) return ts(((GenericArrayType) t).getGenericComponentType()) + "[]";
        return t.getTypeName();
    }

    // Konstanten direkt aus der .class-Datei (ohne die Klasse zu initialisieren)
    static Map<String, Object> constants(Class<?> c) {
        return constCache.computeIfAbsent(c.getName(), n -> {
            try (InputStream in = loader.getResourceAsStream(n.replace('.', '/') + ".class")) {
                if (in == null) return Collections.emptyMap();
                return ClassFile.parse(in.readAllBytes()).constants;
            } catch (Exception e) {
                warnings.add("Konstanten von " + n + " nicht lesbar (" + e + ")");
                return Collections.emptyMap();
            }
        });
    }

    static String literal(Class<?> t, Object v) {
        if (t == String.class) return strLit((String) v);
        if (t == boolean.class) return ((Integer) v) != 0 ? "true" : "false";
        if (t == char.class) return "(char) " + v;
        if (t == byte.class) return "(byte) " + v;
        if (t == short.class) return "(short) " + v;
        if (t == int.class) return String.valueOf(v);
        if (t == long.class) return v + "L";
        if (t == float.class) {
            float f = (Float) v;
            if (Float.isNaN(f)) return "(0.0f / 0.0f)";
            if (Float.isInfinite(f)) return f > 0 ? "(1.0f / 0.0f)" : "(-1.0f / 0.0f)";
            return Float.toHexString(f) + "f";
        }
        if (t == double.class) {
            double d = (Double) v;
            if (Double.isNaN(d)) return "(0.0d / 0.0d)";
            if (Double.isInfinite(d)) return d > 0 ? "(1.0d / 0.0d)" : "(-1.0d / 0.0d)";
            return Double.toHexString(d) + "d";
        }
        return nonConst(t);
    }

    static String strLit(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20 || ch > 0x7e) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    static String nonConst(Class<?> t) {
        if (t == int.class) return "java.lang.Integer.valueOf(0)";
        if (t == long.class) return "java.lang.Long.valueOf(0L)";
        if (t == boolean.class) return "java.lang.Boolean.valueOf(false)";
        if (t == char.class) return "java.lang.Character.valueOf('a')";
        if (t == byte.class) return "java.lang.Byte.valueOf((byte) 0)";
        if (t == short.class) return "java.lang.Short.valueOf((short) 0)";
        if (t == float.class) return "java.lang.Float.valueOf(0f)";
        if (t == double.class) return "java.lang.Double.valueOf(0d)";
        return "null";
    }

    // ───────────────────────────── Hilfen ─────────────────────────────

    static boolean compile(List<File> files, String cp, Path outDir, boolean nowarn) throws IOException {
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        boolean ok = compile(files, cp, outDir, nowarn, d);
        if (!ok) for (Diagnostic<? extends JavaFileObject> x : d.getDiagnostics())
            if (x.getKind() == Diagnostic.Kind.ERROR) System.err.println(x);
        return ok;
    }

    static boolean compile(List<File> files, String cp, Path outDir, boolean nowarn,
                           DiagnosticCollector<JavaFileObject> diag) throws IOException {
        deleteTree(outDir);
        Files.createDirectories(outDir);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) fail("Kein Java-Compiler gefunden. Bitte mit einem JDK (nicht nur JRE) starten.");
        List<String> opts = new ArrayList<>(List.of("-encoding", "UTF-8", "-nowarn", "-proc:none", "-Xmaxerrs", "1000",
                                                    "-d", outDir.toString()));
        opts.add("-cp");
        opts.add(cp == null ? "" : cp);
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diag, Locale.ROOT, StandardCharsets.UTF_8)) {
            return jc.getTask(null, fm, diag, opts, null, fm.getJavaFileObjectsFromFiles(files)).call();
        }
    }

    /** Kompiliert gegen CATIA und sammelt dabei alle im Quelltext verwendeten Klassen (auch reine Konstanten). */
    static Set<String> compileAndCollect(List<File> files, String cp, Path outDir) throws IOException {
        deleteTree(outDir);
        Files.createDirectories(outDir);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) fail("Kein Java-Compiler gefunden. Bitte mit einem JDK (nicht nur JRE) starten.");
        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        List<String> opts = List.of("-encoding", "UTF-8", "-nowarn", "-proc:none", "-d", outDir.toString(), "-cp", cp);
        Set<String> found = new TreeSet<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diag, Locale.ROOT, StandardCharsets.UTF_8)) {
            com.sun.source.util.JavacTask task = (com.sun.source.util.JavacTask)
                jc.getTask(null, fm, diag, opts, null, fm.getJavaFileObjectsFromFiles(files));
            Iterable<? extends com.sun.source.tree.CompilationUnitTree> units = task.parse();
            task.analyze();
            boolean errors = diag.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            if (!errors) {
                com.sun.source.util.Trees trees = com.sun.source.util.Trees.instance(task);
                javax.lang.model.util.Elements els = task.getElements();
                for (com.sun.source.tree.CompilationUnitTree u : units) {
                    new com.sun.source.util.TreePathScanner<Void, Void>() {
                        void take(javax.lang.model.element.Element e) {
                            while (e != null && !(e instanceof javax.lang.model.element.TypeElement)) {
                                if (e instanceof javax.lang.model.element.PackageElement) return;
                                e = e.getEnclosingElement();
                            }
                            if (e != null) found.add(els.getBinaryName((javax.lang.model.element.TypeElement) e).toString());
                        }
                        @Override public Void visitIdentifier(com.sun.source.tree.IdentifierTree t, Void v) {
                            take(trees.getElement(getCurrentPath())); return super.visitIdentifier(t, v);
                        }
                        @Override public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree t, Void v) {
                            take(trees.getElement(getCurrentPath())); return super.visitMemberSelect(t, v);
                        }
                    }.scan(u, null);
                }
                task.generate();
            }
            errors = diag.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            if (errors) {
                for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics())
                    if (d.getKind() == Diagnostic.Kind.ERROR) System.err.println(d);
                fail("Kompilieren gegen CATIA fehlgeschlagen (Meldungen oben).");
            }
        }
        return found;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(x);
        }
    }

    static void step(String m) { System.out.println(); System.out.println("== " + m); }
    static void info(String m) { System.out.println("   " + m); }
    static void ok(String m) { System.out.println("   " + m); }
    static void fail(String m) { System.err.println(); System.err.println("FEHLER: " + m); System.exit(1); }

    /** Minimaler Leser für .class-Dateien: Konstantenpool, verwendete Klassen, Feldkonstanten. */
    static final class ClassFile {
        Object[] cp; int[] tags;
        final Set<Integer> classIdx = new HashSet<>();
        final List<String> descriptors = new ArrayList<>();
        final Map<String, Object> constants = new HashMap<>();
        String thisName;

        static ClassFile parse(byte[] b) throws IOException {
            ClassFile f = new ClassFile();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("keine Klassendatei");
            in.readUnsignedShort(); in.readUnsignedShort();
            int n = in.readUnsignedShort();
            f.cp = new Object[n]; f.tags = new int[n];
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                f.tags[i] = tag;
                switch (tag) {
                    case 1: f.cp[i] = in.readUTF(); break;
                    case 3: f.cp[i] = in.readInt(); break;
                    case 4: f.cp[i] = in.readFloat(); break;
                    case 5: f.cp[i] = in.readLong(); i++; break;
                    case 6: f.cp[i] = in.readDouble(); i++; break;
                    case 7: case 8: case 16: case 19: case 20: f.cp[i] = in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        f.cp[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()}; break;
                    case 15: f.cp[i] = new int[]{in.readUnsignedByte(), in.readUnsignedShort()}; break;
                    default: throw new IOException("unbekannter Eintrag " + tag);
                }
            }
            for (int i = 1; i < n; i++) {
                if (f.tags[i] == 7) f.classIdx.add((Integer) f.cp[i]);
                else if (f.tags[i] == 12) f.descriptors.add(f.utf(((int[]) f.cp[i])[1]));
                else if (f.tags[i] == 16) f.descriptors.add(f.utf((Integer) f.cp[i]));
            }
            in.readUnsignedShort();
            f.thisName = f.utf((Integer) f.cp[in.readUnsignedShort()]);
            in.readUnsignedShort();
            int ni = in.readUnsignedShort();
            for (int i = 0; i < ni; i++) in.readUnsignedShort();
            int nf = in.readUnsignedShort();
            for (int i = 0; i < nf; i++) f.member(in, true);
            int nm = in.readUnsignedShort();
            for (int i = 0; i < nm; i++) f.member(in, false);
            f.attributes(in);
            return f;
        }

        String utf(int i) { return (String) cp[i]; }

        void member(DataInputStream in, boolean field) throws IOException {
            in.readUnsignedShort();
            String name = utf(in.readUnsignedShort());
            descriptors.add(utf(in.readUnsignedShort()));
            int na = in.readUnsignedShort();
            for (int i = 0; i < na; i++) {
                String an = utf(in.readUnsignedShort());
                int len = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                if (field && an.equals("ConstantValue")) {
                    int idx = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                    Object v = cp[idx];
                    if (tags[idx] == 8) v = utf((Integer) v);
                    constants.put(name, v);
                } else if (an.equals("Signature")) {
                    descriptors.add(utf(((data[0] & 0xff) << 8) | (data[1] & 0xff)));
                }
            }
        }

        void attributes(DataInputStream in) throws IOException {
            int na = in.readUnsignedShort();
            for (int i = 0; i < na; i++) {
                String an = utf(in.readUnsignedShort());
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                if (an.equals("Signature")) descriptors.add(utf(((data[0] & 0xff) << 8) | (data[1] & 0xff)));
            }
        }

        Set<String> referencedClasses() {
            Set<String> r = new TreeSet<>();
            for (int i : classIdx) {
                String s = utf(i);
                if (s.startsWith("[")) addDesc(r, s); else r.add(s.replace('/', '.'));
            }
            for (String d : descriptors) addDesc(r, d);
            r.remove(thisName.replace('/', '.'));
            return r;
        }

        static void addDesc(Set<String> r, String d) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("L([^;<]+)[;<]").matcher(d);
            while (m.find()) r.add(m.group(1).replace('/', '.'));
        }
    }
}
