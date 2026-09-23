import com.hammam.attendai.domain.attendance.*
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.security.Pbkdf2Sha256
import com.hammam.attendai.ble.RotatingPresenceToken
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

fun main(){
 val p=AttendancePolicy(.85,.5,10,10,.2,120,300,.6); val e=AttendanceEngine()
 check(e.compute(AttendanceComputationInput(0,3600,listOf(PresenceInterval(0,3600)),.9),p).finalStatus==FinalAttendanceStatus.PRESENT)
 check(e.compute(AttendanceComputationInput(0,3600,emptyList(),.9),p).finalStatus==FinalAttendanceStatus.ABSENT)
 check(e.compute(AttendanceComputationInput(0,3600,listOf(PresenceInterval(0,3600)),.1),p).finalStatus==FinalAttendanceStatus.MANUAL_REVIEW)
 val sm=PresenceStateMachine(120); val lost=sm.reduce(PresenceStateMachine.Snapshot(AttendanceState.PRESENT,5,null),PresenceEventType.LOST,10); check(sm.onTick(lost,100).state==AttendanceState.TEMPORARILY_MISSING); check(sm.onTick(lost,131).state==AttendanceState.LEFT)
 check(AntiFraudEngine().evaluate(FraudContext(sameDeviceStudentCount=2)).isNotEmpty())
 var elapsed=1_000L; val guard=SessionClockGuard({elapsed},5_000); guard.start("s",10_000); elapsed+=1_000; check(!guard.observe("s",11_000)); elapsed+=1_000; check(guard.observe("s",30_000))
 val password="1234مرحبا".toCharArray(); val salt=ByteArray(16){it.toByte()}; val spec=PBEKeySpec(password,salt,1000,256); val expected=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded; spec.clearPassword(); check(expected.contentEquals(Pbkdf2Sha256.deriveFallback(password,salt,1000,32)))
 val bleSecret=ByteArray(32){(it+1).toByte()};val bleNow=1_700_000_000_000L;val token=RotatingPresenceToken.token(bleSecret,"device-public",bleNow);check(RotatingPresenceToken.accepts(bleSecret,"device-public",token,bleNow));check(!RotatingPresenceToken.accepts(ByteArray(32),"device-public",token,bleNow))
 println("Core attendance validation passed")
}
