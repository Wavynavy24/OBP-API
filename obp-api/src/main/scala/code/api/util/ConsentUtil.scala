package code.api.util

import code.consumer.Consumers
import code.entitlement.Entitlement
import code.users.Users
import code.views.Views
import com.openbankproject.commons.model._
import net.liftweb.common.{Box, Failure, Full}
import net.liftweb.json.JsonParser.ParseException
import net.liftweb.json.MappingException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ConsentJWT(createdByUserId: String,
                      sub: String, // An identifier for the user, unique among all OBP-API users and never reused
                      iss: String, // The Issuer Identifier for the Issuer of the response.
                      aud: String, // Identifies the audience that this ID token is intended for. It must be one of the OBP-API client IDs of your application. 
                      jti: String, // (JWT ID) claim provides a unique identifier for the JWT.
                      iat: Long, // The "iat" (issued at) claim identifies the time at which the JWT was issued. Represented in Unix time (integer seconds).
                      nbf: Long, // The "nbf" (not before) claim identifies the time before which the JWT MUST NOT be accepted for processing. Represented in Unix time (integer seconds).
                      exp: Long, // The "exp" (expiration time) claim identifies the expiration time on or after which the JWT MUST NOT be accepted for processing. Represented in Unix time (integer seconds).
                      name: Option[String],
                      email: Option[String],
                      entitlements: List[Role],
                      views: List[ConsentView]) {
  def toConsent(): Consent = {
    Consent(
      createdByUserId=this.createdByUserId, 
      subject=this.sub, 
      issuer=this.iss,
      consumerKey=this.aud, 
      consentId=this.jti, 
      issuedAt=this.iat, 
      validFrom=this.nbf, 
      validTo=this.exp,
      name=this.name, 
      email=this.email, 
      entitlements=this.entitlements,
      views=this.views
    )
  }
}

case class Role(role_name: String, 
                bank_id: String
               )
case class ConsentView(bank_id: String, 
                       account_id: String,
                       view_id : String
                      )

case class Consent(createdByUserId: String,
                   subject: String,
                   issuer: String,
                   consumerKey: String,
                   consentId: String,
                   issuedAt: Long,
                   validFrom: Long,
                   validTo: Long,
                   name: Option[String],
                   email: Option[String],
                   entitlements: List[Role],
                   views: List[ConsentView]
                  ) {
  def toConsentJWT(): ConsentJWT = {
    ConsentJWT(
      createdByUserId=this.createdByUserId,
      sub=this.subject,
      iss=this.issuer,
      aud=this.consumerKey,
      jti=this.consentId,
      iat=this.issuedAt,
      nbf=this.validFrom,
      exp=this.validTo,
      name=this.name,
      email=this.email,
      entitlements=this.entitlements,
      views=this.views
    )
  }
}

object Consent {
  
  private def verifyHmacSignedJwt(jwtToken: String): Boolean = {
    val secret = APIUtil.getPropsValue("consent.jwt_secret", "Cannot get your at least 256 bit secret")
    JwtUtil.verifyHmacSignedJwt(jwtToken, secret)
  }
  
  private def checkConsumerKey(consent: ConsentJWT): Boolean = {
    Consumers.consumers.vend.getConsumerByConsumerKey(consent.aud).isDefined
  }
  
  private def checkConsent(consent: ConsentJWT, consentIdAsJwt: String): Box[Boolean] = {
    verifyHmacSignedJwt(consentIdAsJwt) match {
      case true =>
        (System.currentTimeMillis / 1000) match {
          case currentTimeInSeconds if currentTimeInSeconds < consent.nbf =>
            Failure("The time Consent-ID token was issued is set in the future.")
          case currentTimeInSeconds if currentTimeInSeconds > consent.exp =>
            Failure("Consent-Id is expired.")
          case _ =>
            checkConsumerKey(consent)  match {
              case true => 
                Full(true)
              case false => 
                Failure("There is no Consumer Key: " + consent.aud)
            }
        }
      case false =>
        Failure("Consent-Id JWT value couldn't be verified.")
    }
  }

  private def getOrCreateUser(subject: String, issuer: String, name: Option[String], email: Option[String]): Future[Box[User]] = {
    Users.users.vend.getOrCreateUserByProviderIdFuture(
      provider = issuer,
      idGivenByProvider = subject,
      name = name,
      email = email
    )
  }

  private def addEntitlements(user: User, consent: ConsentJWT): Box[User] = {
    def addConsentEntitlements(existingEntitlements: List[Entitlement], entitlement: Role): (Role, String) = {
      ApiRole.availableRoles.exists(_.toString == entitlement.role_name) match { // Check a role name
        case true =>
          val role = ApiRole.valueOf(entitlement.role_name)
          existingEntitlements.exists(_.roleName == entitlement.role_name) match { // Check is a role already added to a user
            case false =>
              val bankId = if (role.requiresBankId) entitlement.bank_id else ""
              Entitlement.entitlement.vend.addEntitlement(bankId, user.userId, entitlement.role_name) match {
                case Full(_) => (entitlement, "AddedOrExisted")
                case _ => (entitlement, "Cannot add the entitlement: " + entitlement)
              }
            case true =>
              (entitlement, "AddedOrExisted")
          }
        case false =>
          (entitlement, "There is no entitlement's name: " + entitlement)
      }
    }

    val entitlements: List[Role] = consent.entitlements
    Entitlement.entitlement.vend.getEntitlementsByUserId(user.userId) match {
      case Full(existingEntitlements) =>
        val triedToAdd =
          for {
            entitlement <- entitlements
          } yield {
            addConsentEntitlements(existingEntitlements, entitlement)
          }
        val failedToAdd: List[(Role, String)] = triedToAdd.filter(_._2 != "AddedOrExisted")
        failedToAdd match {
          case Nil => Full(user)
          case _ => Failure("The entitlements cannot be added. " + failedToAdd.map(_._1).mkString(", "))
        }
      case _ =>
        Failure("Cannot get entitlements for user id: " + user.userId)
    }

  }

  private def addPermissions(user: User, consent: ConsentJWT): Box[User] = {
    val result = 
      for {
        view <- consent.views
      } yield {
        Views.views.vend.revokePermission(ViewIdBankIdAccountId(ViewId(view.view_id), BankId(view.bank_id), AccountId(view.account_id)), user)
        Views.views.vend.addPermission(ViewIdBankIdAccountId(ViewId(view.view_id), BankId(view.bank_id), AccountId(view.account_id)), user)
        "Added"
      }
    if (result.forall(_ == "Added")) Full(user) else Failure("Cannot add permissions to the user with id: " + user.userId)
  }
 
  private def hasConsentInternal(consentIdAsJwt: String): Future[Box[User]] = {
    implicit val dateFormats = net.liftweb.json.DefaultFormats

    def applyConsentRules(consent: ConsentJWT): Future[Box[User]] = {
      // 1. Get or Create a User
      getOrCreateUser(consent.sub, consent.iss, None, None) map {
        case (Full(user)) =>
          // 2. Assign entitlements to the User
          addEntitlements(user, consent) match {
            case (Full(user)) =>
              // 3. Assign views to the User
              addPermissions(user, consent)
            case everythingElse =>
              everythingElse
          }
        case _ =>
          Failure("Cannot create or get the user based on: " + consentIdAsJwt)
      }
    }

    JwtUtil.getSignedPayloadAsJson(consentIdAsJwt) match {
      case Full(jsonAsString) =>
        try {
          val consent = net.liftweb.json.parse(jsonAsString).extract[ConsentJWT]
          checkConsent(consent, consentIdAsJwt) match { // Check is it Consent-Id expired
            case (Full(true)) => // OK
              applyConsentRules(consent)
            case failure@Failure(_, _, _) => // Handled errors
              Future(failure)
            case _ => // Unexpected errors
              Future(Failure("Cannot check is Consent-Id expired."))
          }
        } catch { // Possible exceptions
          case e: ParseException => Future(Failure("ParseException: " + e.getMessage))
          case e: MappingException => Future(Failure("MappingException: " + e.getMessage))
          case e: Exception => Future(Failure("parsing failed: " + e.getMessage))
        }
      case failure@Failure(_, _, _) =>
        Future(failure)
      case _ =>
        Future(Failure("Cannot extract data from: " + consentIdAsJwt))
    }
  }
  
  private def hasConsent(consentIdAsJwt: String, calContext: Option[CallContext]): Future[(Box[User], Option[CallContext])] = {
    hasConsentInternal(consentIdAsJwt) map (result => (result, calContext))
  }
  
  def applyRules(consentId: Option[String], callContext: Option[CallContext]): Future[(Box[User], Option[CallContext])] = {
    consentId match {
      case Some(consentId) => hasConsent(consentId, callContext)
      case None => Future((Failure("Cannot get Consent-Id"), callContext))
    }
  }
  
}
