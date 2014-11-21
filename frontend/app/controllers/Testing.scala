package controllers

import com.typesafe.scalalogging.slf4j.LazyLogging
import play.api.mvc.{Cookie, Controller}
import utils.TestUsers.testUsers

object Testing extends Controller with LazyLogging {

  val analyticsOffCookie = Cookie("ANALYTICS_OFF_KEY", "true", httpOnly = false)

  def testUser = AuthenticatedStaffNonMemberAction { implicit request =>
    val testUserString = testUsers.generate()
    logger.info(s"Generated test user string $testUserString for ${request.user.email}")
    Ok(views.html.testing.testUsers(testUserString)).withCookies(analyticsOffCookie)
  }

  def analyticsOff = CachedAction {
    Ok(s"${analyticsOffCookie.name} cookie dropped").withCookies(analyticsOffCookie)
  }
}
