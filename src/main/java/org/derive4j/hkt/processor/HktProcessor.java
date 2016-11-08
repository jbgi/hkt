/*
    Copyright (c) 2016, Grégoire Neuville and Derive4J HKT contributors.
    All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this
      list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

    * Neither the name of hkt nor the names of its
      contributors may be used to endorse or promote products derived from
      this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.derive4j.hkt.processor;

import com.google.auto.service.AutoService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.derive4j.hkt.Hkt;
import org.derive4j.hkt.__;
import org.derive4j.hkt.processor.DataTypes.*;
import org.derive4j.hkt.processor.JavaCompiler.OpenJdkSpecificApi;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.*;
import javax.tools.Diagnostic;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.lang.Math.min;
import static java.lang.String.format;
import static org.derive4j.hkt.processor._HkTypeError.*;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("*")
public final class HktProcessor extends AbstractProcessor {

    private Filer Filer;
    private Types Types;
    private Elements Elts;
    private Messager Messager;
    private GenCode GenCode;
    private Optional<JavaCompiler.JdkSpecificApi> JdkSpecificApi;

    private TypeElement __Elt;

    private ArrayList<DataTypes.HktDecl> hktDecls = new ArrayList<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        Filer = processingEnv.getFiler();
        Types = processingEnv.getTypeUtils();
        Elts = processingEnv.getElementUtils();
        GenCode = new GenCode(Elts, Types, Filer,  processingEnv.getMessager());
        Messager = processingEnv.getMessager();
        JdkSpecificApi = jdkSpecificApi(processingEnv);

        __Elt = Elts.getTypeElement(__.class.getCanonicalName());

    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {

            final Stream<TypeElement> allTypes = ElementFilter
                .typesIn(roundEnv.getRootElements())
                //.parallelStream()
                .stream()
                .flatMap(tel -> Stream.concat(Stream.of(tel), allInnerTypes(tel)));

            final Stream<HktDecl> targetTypes = allTypes.flatMap(this::asHktDecl);

            final Stream<Valid> validations = targetTypes.flatMap(this::checkHktType);

            final Stream<IO<Unit>> effects = validations.map(_Valid.cases()
                .Success(hktDecl -> IO.effect(() -> hktDecls.add(hktDecl)))
                .Fail(this::reportError));

            IO.unsafeRun(effects);
        } else {
            IO.unsafeRun(hktDecls.stream().filter(new Predicate<HktDecl>() {
                Map<Object,Boolean> seen = new HashMap<>();
                @Override
                public boolean test(HktDecl hktDecl) {
                    return seen.putIfAbsent(_HktDecl.getTypeConstructor(hktDecl), Boolean.TRUE) == null;
                }
            }).map(GenCode::run));
            System.out.println("Ok, processing over");
            System.out.println("Further elements " + roundEnv.getRootElements().size());

        }
        return false;
    }

    static IO<Unit> test(HktDecl hktDecl) {
        return hktDecl.match((typeConstructor, hktInterface, conf) -> IO.effect(() -> {
            System.out.println("TypeElement : " + typeConstructor + "\nDeclaredType : " + hktInterface);
        }));
    }

    private static Optional<JavaCompiler.JdkSpecificApi> jdkSpecificApi(ProcessingEnvironment processingEnv) {
        return processingEnv.getElementUtils().getTypeElement("com.sun.source.util.Trees") != null
            ? Optional.of(new OpenJdkSpecificApi(processingEnv))
            : Optional.empty();
    }

    private Stream<TypeElement> allInnerTypes(TypeElement tel) {
        final Stream<TypeElement> memberTypes =
            ElementFilter.typesIn(tel.getEnclosedElements()).stream();

        final Stream<TypeElement> localTypes =
            JdkSpecificApi.map(jdkSpecificApi -> jdkSpecificApi.localTypes(tel)).orElse(Stream.empty());

        final List<TypeElement> allTypes =
            Stream.concat(memberTypes, localTypes).collect(Collectors.toList());

        return allTypes.isEmpty()
            ? Stream.empty()
            : Stream.concat
            //(allTypes.stream(), allTypes.parallelStream().flatMap(this::allInnerTypes))
                (allTypes.stream(), allTypes.stream().flatMap(this::allInnerTypes));
    }

    private Stream<HktDecl> asHktDecl(TypeElement tEl) {
        return tEl.getInterfaces().stream()
            .map(this::asHktInterface)
            .flatMap(Opt::asStream)
            .limit(1)
            .map(hktInterface -> _HktDecl.of(tEl, hktInterface, hktConf(tEl)));
    }

    private Stream<Valid> checkHktType(HktDecl hktDecl) {
        return Stream.of(
            checkHktInterfaceNotRawType(hktDecl),
            checkAtLeastOneTypeParameter(hktDecl),
            checkRightHktInterface(hktDecl),
            checkTypeParameters(hktDecl),
            checkTCWitness(hktDecl),
            checkNestedTCWitnessHasNoTypeParameter(hktDecl),
            checkNestedTCWitnessIsStaticFinal(hktDecl)
        );
    }

    private Valid checkHktInterfaceNotRawType(HktDecl hktDecl) {
        return _HktDecl.getHktInterface(hktDecl).getTypeArguments().isEmpty()
            ? _Valid.Fail(hktDecl, HKTInterfaceDeclIsRawType())
            : _Valid.Success(hktDecl);
    }

    private Valid checkAtLeastOneTypeParameter(HktDecl hktDecl) {
        return _HktDecl.getTypeConstructor(hktDecl).getTypeParameters().isEmpty()
            ? _Valid.Fail(hktDecl, HKTypesNeedAtLeastOneTypeParameter())
            : _Valid.Success(hktDecl);
    }

    private Valid checkRightHktInterface(HktDecl hktDecl) {
        return Visitors.asTypeElement.visit(_HktDecl.getHktInterface(hktDecl).asElement())
            .filter(hktInterfaceElement ->
                _HktDecl.getTypeConstructor(hktDecl).getTypeParameters().size() + 1
                    != hktInterfaceElement.getTypeParameters().size())
            .map(__ -> _Valid.Fail(hktDecl, WrongHKTInterface()))
            .orElse(_Valid.Success(hktDecl));
    }

    private Valid checkTypeParameters(HktDecl hktDecl) {
        final List<? extends TypeParameterElement> typeParameters =
            _HktDecl.getTypeConstructor(hktDecl).getTypeParameters();
        final List<? extends TypeMirror> typeArguments =
            _HktDecl.getHktInterface(hktDecl).getTypeArguments();

        List<TypeParameterElement> typeParamsInError = IntStream
            .range(0, min(typeParameters.size(), typeArguments.size() - 1))
            .filter(i -> !Types.isSameType(typeParameters.get(i).asType(), typeArguments.get(i + 1)))
            .mapToObj(typeParameters::get)
            .collect(Collectors.toList());

        return typeParamsInError.isEmpty()
            ? _Valid.Success(hktDecl)
            : _Valid.Fail(hktDecl, NotMatchingTypeParams(typeParamsInError));
    }

    private Valid checkTCWitness(HktDecl hktDecl) {
        return _HktDecl
            .getHktInterface(hktDecl).getTypeArguments().stream().findFirst()
            .flatMap(witnessTm -> asValidTCWitness(_HktDecl.getTypeConstructor(hktDecl), witnessTm))
            .isPresent()
            ? _Valid.Success(hktDecl)
            : _Valid.Fail(hktDecl, TCWitnessMustBeNestedClassOrClass());
    }
    private Optional<DeclaredType> asValidTCWitness(TypeElement typeConstructor, TypeMirror witnessTm) {
        return Visitors.asDeclaredType.visit(witnessTm)
            .filter(witness ->
                Types.isSameType(witness, Types.erasure(typeConstructor.asType()))
                    || Types.isSameType(witness, Types.getDeclaredType(typeConstructor, typeConstructor.getTypeParameters().stream()
                    .map(__ -> Types.getWildcardType(null, null))
                    .toArray(TypeMirror[]::new)))
                    || witness.asElement().getEnclosingElement().equals(typeConstructor));
    }

    private Valid checkNestedTCWitnessHasNoTypeParameter(HktDecl hktDecl) {
        return _HktDecl
            .getHktInterface(hktDecl).getTypeArguments().stream().findFirst()
            .flatMap(witnessTm ->
                Visitors.asDeclaredType.visit(witnessTm).map(DeclaredType::asElement).flatMap(Visitors.asTypeElement::visit)
                    .filter(witness -> witness.getEnclosingElement().equals(_HktDecl.getTypeConstructor(hktDecl)))
                    .flatMap(witness -> witness.getTypeParameters().isEmpty()
                        ? Optional.empty()
                        : Optional.of(NestedTCWitnessMustBeSimpleType(witness))))
            .map(Valid.Fail(hktDecl))
            .orElse(_Valid.Success(hktDecl));
    }

    private Valid checkNestedTCWitnessIsStaticFinal(HktDecl hktDecl) {
        final TypeElement typeConstructor = _HktDecl.getTypeConstructor(hktDecl);

        return _HktDecl
            .getHktInterface(hktDecl).getTypeArguments().stream().findFirst()
            .flatMap(witnessTm ->
                Visitors.asDeclaredType.visit(witnessTm).map(DeclaredType::asElement).flatMap(Visitors.asTypeElement::visit)
                    .filter(witness -> witness.getEnclosingElement().equals(typeConstructor))
                    .flatMap(witness -> (witness.getKind() == ElementKind.INTERFACE ||  witness.getModifiers().contains(Modifier.STATIC))
                        && (!typeConstructor.getModifiers().contains(Modifier.PUBLIC) || typeConstructor.getKind() == ElementKind.INTERFACE || witness.getModifiers().contains(Modifier.PUBLIC))
                        ? Optional.empty()
                        : Optional.of(NestedTCWitnessMustBeStaticFinal(witness))))
            .map(Valid.Fail(hktDecl))
            .orElse(_Valid.Success(hktDecl));
    }


    private Optional<DeclaredType> asHktInterface(TypeMirror tm) {
        return Visitors.asDeclaredType.visit(tm)
            .filter(declaredType ->  Elts.getPackageOf(declaredType.asElement()).equals(Elts.getPackageOf(__Elt)))
            .filter(declaredType -> Types.isSubtype(declaredType, Types.erasure(__Elt.asType())));
    }

    private IO<Unit> reportError(HktDecl hkt, HkTypeError error) {
        final TypeElement typeElement = _HktDecl.getTypeConstructor(hkt);
        final HktConf conf = _HktDecl.getConf(hkt);

        return _HkTypeError.cases()
            .HKTInterfaceDeclIsRawType(IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, hKTInterfaceDeclIsRawTypeErrorMessage(typeElement, conf), typeElement)))

            .HKTypesNeedAtLeastOneTypeParameter(IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, hKTypesNeedAtLeastOneTypeParameterErrorMessage(typeElement), typeElement)))

            .WrongHKTInterface(IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, wrongHKTInterfaceErrorMessage(typeElement, conf), typeElement)))

            .NotMatchingTypeParams(typeParameterElements -> IO.effect(() ->
                typeParameterElements.stream().forEach(typeParameterElement -> Messager.printMessage
                    (Diagnostic.Kind.ERROR, notMatchingTypeParamErrorMessage(typeElement, conf), typeParameterElement))))

            .TCWitnessMustBeNestedClassOrClass(IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, tcWitnessMustBeNestedClassOrClassErrorMessage(typeElement, conf), typeElement)))

            .NestedTCWitnessMustBeSimpleType(tcWitnessElement -> IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, nestedTCWitnessMustBeSimpleTypeErrorMessage(), tcWitnessElement)))

            .NestedTCWitnessMustBeStaticFinal(tcWitnessElement -> IO.effect(() -> Messager.printMessage
                (Diagnostic.Kind.ERROR, nestedTCWitnessMustBePublicStaticErrorMessage(typeElement), tcWitnessElement)))

            .apply(error);
    }

    private String hKTInterfaceDeclIsRawTypeErrorMessage(TypeElement tel, HktConf conf) {
        return format("%s interface declaration is missing type arguments:%n%s",
            implementedHktInterfaceName(tel),
            expectedHktInterfaceMessage(tel, conf));
    }

    private String hKTypesNeedAtLeastOneTypeParameterErrorMessage(TypeElement tel) {
        return format("%s need at least one type parameter to correctly implement %s",
            tel.toString(), implementedHktInterfaceName(tel));
    }

    private String wrongHKTInterfaceErrorMessage(TypeElement tel, HktConf conf) {
        return format("%s is not the correct interface to use.%nGiven the number of type parameters, %s",
            implementedHktInterfaceName(tel), expectedHktInterfaceMessage(tel, conf));
    }

    private String notMatchingTypeParamErrorMessage(TypeElement tel, HktConf conf) {
        return format("The type parameters of %s must appear in the same order in the declaration of %s:%n%s",
            tel.toString(), implementedHktInterfaceName(tel), expectedHktInterfaceMessage(tel, conf));
    }

    private String tcWitnessMustBeNestedClassOrClassErrorMessage(TypeElement tel, HktConf conf) {
        return format("Type constructor witness (first type argument of %s) is incorrect:%n%s",
            implementedHktInterfaceName(tel), expectedHktInterfaceMessage(tel, conf));
    }

    private String nestedTCWitnessMustBeSimpleTypeErrorMessage() {
        return "The nested class used as type constructor witness must not take any type parameter";
    }

    private String nestedTCWitnessMustBePublicStaticErrorMessage(TypeElement tel) {
        return format("The nested class used as type constructor witness must be '%sstatic final'.",
            tel.getModifiers().contains(Modifier.PUBLIC) ? "public " : "");
    }

    private String implementedHktInterfaceName(TypeElement tel) {
        return tel.getInterfaces().stream().map(this::asHktInterface).flatMap(Opt::asStream).findFirst()
            .map(DeclaredType::asElement).map(Element::toString).orElse("");
    }

    private String expectedHktInterfaceMessage(TypeElement tel, HktConf conf) {
        final String witnessTypeName = _HktConf.getWitnessTypeName(conf);

        return format("%s should %s %s", tel.toString(), tel.getKind() == ElementKind.CLASS ? "implements" : "extends",

            Opt.cata(tel.getInterfaces().stream()
                    .map(this::asHktInterface)
                    .flatMap(Opt::asStream)
                    .map(hktInterface -> hktInterface.getTypeArguments().stream()
                        .findFirst().flatMap(tm -> asValidTCWitness(tel, tm)))
                    .flatMap(Opt::asStream)
                    .findFirst()

                , tcWitness -> expectedHktInterface(tel, tcWitness.toString())

                , () -> tel.getTypeParameters().size() <= 1

                    ? expectedHktInterface(tel, Types.getDeclaredType(tel, tel.getTypeParameters().stream()
                    .map(__ -> Types.getWildcardType(null, null))
                    .toArray(TypeMirror[]::new)).toString())

                    : format("%s with %s being the following nested class of %s:%n    %s"
                    , expectedHktInterface(tel, witnessTypeName)
                    , witnessTypeName
                    , tel.toString()
                    , "public static final class " + witnessTypeName + " {}")));
    }

    private String expectedHktInterface(TypeElement tel, String witness) {
        int nbTypeParameters = tel.getTypeParameters().size();
        return format("%s%s<%s, %s>", __Elt.getQualifiedName().toString(),
            nbTypeParameters <= 1 ? "" : String.valueOf(nbTypeParameters),
            witness,
            tel.getTypeParameters().stream().map(Element::asType).map(TypeMirror::toString)
                .reduce((tp1, tp2) -> tp1 + ", " + tp2).orElse("")
        );
    }

    private HktConf hktConf(Element elt) {
        return maybeHktConf(elt).orElse(HktConf.defaultConfig);
    }

    private Optional<HktConf> maybeHktConf(Element elt) {
        return Opt.cata(selfHktConf(elt)

            , selfConf -> Opt
                .or(parentHktConf(elt).map(pconf -> pconf.mergeWith(selfConf))
                    , () -> Optional.of(selfConf))

            , () -> parentHktConf(elt));
    }

    private Optional<HktConf> parentHktConf(Element elt) {
        return parentElt(elt).flatMap(this::maybeHktConf);
    }

    private Optional<Element> parentElt(Element elt) {
        return Opt.cata(Opt.unNull(elt.getEnclosingElement())
            , Optional::of
            , () -> parentPkg((PackageElement) elt).map(__ -> __));
    }

    private Optional<PackageElement> parentPkg(PackageElement elt) {
        final String[] pkgNames = elt.getQualifiedName().toString().split("\\.");

        if (pkgNames.length == 1) return Optional.empty();
        else {
            final String[] targetNames = Arrays.copyOfRange(pkgNames, 0, pkgNames.length - 1);
            final String targetName = Arrays.stream(targetNames).collect(Collectors.joining("."));

            return Opt.unNull(Elts.getPackageElement(targetName));
        }
    }

    private static Optional<HktConf> selfHktConf(Element elt) {
        return Opt.unNull(elt.getAnnotation(Hkt.class)).map(HktConf::from);
    }


}

