package storymodel4s.fixtures.wog

import java.net.{URL, URLClassLoader}

import munit.FunSuite

import storymodel4s.story.StoryModel

/** Guards fixture initialization in a classloader unaffected by the order of other test suites. */
class WarOfTheGhostsInitializationSuite extends FunSuite:

  test("a fresh classloader can initialize expectations before the model") {
    val loader =
      new FixtureClassLoader(classpathUrls(getClass.getClassLoader), getClass.getClassLoader)
    try
      val expectations = module(loader, "storymodel4s.fixtures.wog.WarOfTheGhostsExpectations$")
      assert(expectations.getClass.getClassLoader eq loader, "expectations escaped isolation")
      val paraphrases =
        expectations.getClass
          .getMethod("recallParaphrases")
          .invoke(expectations)
          .asInstanceOf[Vector[?]]
      assertEquals(paraphrases.size, 10)

      val fixture = module(loader, "storymodel4s.fixtures.wog.WarOfTheGhostsModel$")
      assert(fixture.getClass.getClassLoader eq loader, "model escaped isolation")
      val model = fixture.getClass.getMethod("model").invoke(fixture).asInstanceOf[StoryModel[?]]
      assertEquals(model.graph.situations.size, 71)
    finally loader.close()
  }

  private def module(loader: ClassLoader, name: String): AnyRef =
    loader.loadClass(name).getField("MODULE$").get(null)

  private def classpathUrls(loader: ClassLoader): Array[URL] =
    Iterator
      .iterate(Option(loader))(_.flatMap(current => Option(current.getParent)))
      .takeWhile(_.nonEmpty)
      .flatMap {
        case Some(urlLoader: URLClassLoader) => urlLoader.getURLs
        case _                               => Array.empty[URL]
      }
      .toVector
      .distinct
      .toArray

  private final class FixtureClassLoader(urls: Array[URL], parent: ClassLoader)
      extends URLClassLoader(urls, parent):
    private val isolatedPrefixes = Vector(
      "storymodel4s.fixtures.wog.WarOfTheGhostsExpectations",
      "storymodel4s.fixtures.wog.WarOfTheGhostsModel"
    )

    override protected def loadClass(name: String, resolve: Boolean): Class[?] =
      if isolatedPrefixes.exists(name.startsWith) then
        getClassLoadingLock(name).synchronized {
          val loaded = findLoadedClass(name)
          val cls =
            if loaded != null then loaded
            else
              try findClass(name)
              catch case _: ClassNotFoundException => super.loadClass(name, false)
          if resolve then resolveClass(cls)
          cls
        }
      else super.loadClass(name, resolve)
