package com.github.agourlay.cornichon.examples.superHeroes.server

import cats.data.Validated
import cats.data.Validated.{ Invalid, Valid }

import scala.collection.concurrent.TrieMap
import scala.util.Random
import cats.syntax.option._
import cats.syntax.validated._

class SuperMicroService {

  val publishersBySession = new TrieMap[String, Set[Publisher]]
  val superheroesBySession = new TrieMap[String, Set[SuperHero]]

  private def addBinding[A](id: String, a: A, map: TrieMap[String, Set[A]]) =
    map.get(id).fold[Unit](map += ((id, Set(a)))) { set ⇒
      map.update(id, set + a)
    }

  private def removeBinding[A](id: String, a: A, map: TrieMap[String, Set[A]]) =
    map.get(id).foreach[Unit] { set ⇒
      map.update(id, set - a)
    }

  def createSession(): String = {
    val newSessionId = Random.alphanumeric.take(8).mkString
    publishersBySession += ((newSessionId, initialPublishers))
    superheroesBySession += ((newSessionId, initialSuperheroes))
    newSessionId
  }

  def deleteSession(sessionId: String): Validated[ApiError, Unit] =
    publishersBySession.remove(sessionId)
      .flatMap(_ ⇒ superheroesBySession.remove(sessionId))
      .map(_ ⇒ ())
      .toValid(SessionNotFound(sessionId))

  def publishersBySessionV(sessionId: String) =
    publishersBySession.get(sessionId).toValid(SessionNotFound(sessionId))

  def superheroesBySessionV(sessionId: String) =
    superheroesBySession.get(sessionId).toValid(SessionNotFound(sessionId))

  def publisherByName(sessionId: String, name: String): Validated[ApiError, Publisher] =
    publishersBySessionV(sessionId).andThen { publishers ⇒
      publishers.find(_.name == name).toValid(PublisherNotFound(name))
    }

  def superheroByName(sessionId: String, name: String, protectIdentity: Boolean = false): Validated[ApiError, SuperHero] = {
    val sh = {
      if (name == "random")
        randomSuperhero(sessionId).valid
      else
        superheroesBySessionV(sessionId).andThen { superHeroes ⇒
          superHeroes.find(_.name == name)
            .toValid(SuperHeroNotFound(name))
        }
    }
    sh.map { c ⇒
      if (protectIdentity) c.copy(realName = "XXXXX")
      else c
    }
  }

  def addPublisher(sessionId: String, p: Publisher) =
    publisherByName(sessionId, p.name) match {
      case Valid(_) ⇒
        Invalid(PublisherAlreadyExists(p.name))
      case Invalid(_) ⇒
        addBinding(sessionId, p, publishersBySession)
        Valid(p)
    }

  def updateSuperhero(sessionId: String, s: SuperHero): Validated[ApiError, SuperHero] =
    superheroByName(sessionId, s.name).andThen { _ ⇒
      publisherByName(sessionId, s.publisher.name).andThen { _ ⇒
        deleteSuperhero(sessionId, s.name).andThen { _ ⇒
          addSuperhero(sessionId, s)
        }
      }
    }

  def addSuperhero(sessionId: String, s: SuperHero) =
    publisherByName(sessionId, s.publisher.name).andThen { p ⇒
      superheroByName(sessionId, s.name) match {
        case Valid(_) ⇒
          Invalid(SuperHeroAlreadyExists(s.name))
        case Invalid(_) ⇒
          addBinding(sessionId, s, superheroesBySession)
          Valid(s)
      }
    }

  def deleteSuperhero(sessionId: String, name: String) =
    superheroByName(sessionId, name).map { sh ⇒
      removeBinding(sessionId, sh, superheroesBySession)
      sh
    }

  def allPublishers(session: String) =
    publishersBySession(session)

  def allSuperheroes(session: String) =
    superheroesBySession(session)

  def randomSuperhero(session: String): SuperHero =
    Random.shuffle(superheroesBySession(session).toSeq).head

  private val initialPublishers = Set(
    Publisher("DC", 1934, "Burbank, California"),
    Publisher("Marvel", 1939, "135 W. 50th Street, New York City")
  )

  private val initialSuperheroes = Set(
    SuperHero("Batman", "Bruce Wayne", "Gotham city", hasSuperpowers = false, initialPublishers.head),
    SuperHero("Superman", "Clark Kent", "Metropolis", hasSuperpowers = true, initialPublishers.head),
    SuperHero("GreenLantern", "Hal Jordan", "Coast City", hasSuperpowers = true, initialPublishers.head),
    SuperHero("Spiderman", "Peter Parker", "New York", hasSuperpowers = true, initialPublishers.tail.head),
    SuperHero("IronMan", "Tony Stark", "New York", hasSuperpowers = false, initialPublishers.tail.head)
  )
}