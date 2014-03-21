// PSI_ELEMENT: org.jetbrains.jet.lang.psi.JetProperty
// GROUPING_RULES: org.jetbrains.jet.plugin.findUsages.KotlinDeclarationGroupRuleProvider$KotlinDeclarationGroupingRule
// OPTIONS: usages
package server;

open class A<T> {
    open var <caret>foo: T
}

open class B: A<String>() {
    open var foo: String
        get() {
            println("get")
            return super<A>.foo
        }
        set(value: String) {
            println("set:" + value)
            super<A>.foo = value
        }
}