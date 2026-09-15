// Pure Java event contracts shared by every service. Deliberately has no Spring, no Kafka and
// no project dependencies — see docs/decisions/0002-event-schema-compatibility-policy.md for the
// rules on changing anything in here.
plugins {
    id("shoppinglive.library-conventions")
}
