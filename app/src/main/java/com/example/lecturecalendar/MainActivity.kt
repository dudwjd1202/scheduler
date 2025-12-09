package com.example.lecturecalendar // ⚠️ 본인의 패키지 이름으로 꼭 유지하세요!

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lecturecalendar.databinding.ActivityMainBinding
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 음성 인식 관련 변수
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionIntent: Intent
    private var isRecording = false

    // 분석된 과제 데이터를 저장할 변수 (4단계 캘린더 연동을 위해)
    private var currentAssignment: AssignmentData? = null

    // 권한 요청 런처
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 음성 인식 설정
        setupSpeechRecognizer()

        // 2. 녹음 버튼 클릭 이벤트
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                checkPermissionAndStart()
            }
        }

        // 3. 캘린더 추가 버튼 클릭 시 동작
        binding.btnAddCalendar.setOnClickListener {
            // 현재 분석된 과제 정보가 있다면 캘린더 함수 호출
            currentAssignment?.let { assignment ->
                addToCalendar(assignment)
            } ?: run {
                Toast.makeText(this, "추가할 과제 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 권한 확인 후 시작
    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                binding.tvRecognizedText.text = "듣고 있어요... 말씀하세요!"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "말씀을 이해하지 못했어요."
                    SpeechRecognizer.ERROR_NETWORK -> "인터넷 연결을 확인해주세요 (에러 코드 2)."
                    else -> "에러 발생 (코드: $error)"
                }
                binding.tvRecognizedText.text = message
                stopRecordingUI()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    binding.tvRecognizedText.text = text

                    // 🔥 [핵심] 3단계: 여기서 텍스트 분석 함수를 호출합니다!
                    analyzeText(text)
                }
                stopRecordingUI()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startVoiceRecognition() {
        speechRecognizer.startListening(recognitionIntent)
        isRecording = true
        binding.btnRecord.text = "녹음 중지"
    }

    private fun stopRecording() {
        speechRecognizer.stopListening()
        stopRecordingUI()
    }

    private fun stopRecordingUI() {
        isRecording = false
        binding.btnRecord.text = "녹음 시작"
    }

    // ------------------------------------------------------------------
    // 👇 여기서부터가 3단계 핵심 로직입니다 (텍스트 분석)
    // ------------------------------------------------------------------

    // 텍스트를 분석해서 날짜와 제목을 뽑아내는 메인 함수
    private fun analyzeText(text: String) {
        val dateResult = extractDate(text) // 날짜 뽑기
        val titleResult = extractTitle(text) // 제목 뽑기

        // 결과 저장
        val assignment = AssignmentData(
            title = titleResult,
            dueDate = dateResult,
            isReady = dateResult.isNotEmpty() && titleResult.isNotEmpty()
        )
        currentAssignment = assignment

        // 화면 업데이트
        updateResultUI(assignment)
    }

    // 1. 날짜 추출 로직 (오늘, 내일, 다음 주 O요일)
    private fun extractDate(text: String): String {
        val today = LocalDate.now()
        var targetDate = today

        // "다음 주" 라는 말이 있는지 확인
        val isNextWeek = text.contains("다음 주")
        if (isNextWeek) {
            // 일단 다음 주 일요일로 날짜를 옮겨둠 (기준점 이동)
            targetDate = today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        }

        // 요일 찾기 (월~일)
        val dayMap = mapOf(
            "월요일" to DayOfWeek.MONDAY, "화요일" to DayOfWeek.TUESDAY,
            "수요일" to DayOfWeek.WEDNESDAY, "목요일" to DayOfWeek.THURSDAY,
            "금요일" to DayOfWeek.FRIDAY, "토요일" to DayOfWeek.SATURDAY,
            "일요일" to DayOfWeek.SUNDAY
        )

        var foundDay = false
        for ((key, dayOfWeek) in dayMap) {
            if (text.contains(key)) {
                // 요일이 발견되면 해당 요일로 날짜 설정
                if (isNextWeek) {
                    // "다음 주" + "화요일" -> 다음 주 기준 화요일 찾기
                    targetDate = targetDate.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                } else {
                    // 그냥 "화요일" -> 이번 주 돌아오는 화요일 찾기
                    targetDate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                }
                foundDay = true
                break
            }
        }

        // 요일 언급이 없고 "내일"이라고 한 경우
        if (!foundDay && text.contains("내일")) {
            targetDate = today.plusDays(1)
            foundDay = true
        }

        // 날짜를 못 찾았거나, 오늘 날짜 그대로라면 실패 처리 (빈 문자열 반환)
        if (!foundDay && !text.contains("오늘")) {
            return ""
        }

        // yyyyMMdd 형식으로 변환해서 반환 (예: 20251125)
        return targetDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    }

    // 2. 제목 추출 로직 ("~까지", "~제출" 앞의 단어들)
    // 수정된 제목 추출 함수
    private fun extractTitle(text: String): String {
        var result = text

        // 1. 핵심 동사("제출", "마감", "준비") 앞부분만 가져오기
        // 예: "화요일까지 과제 제출해" -> "화요일까지 과제 "
        val endKeywords = listOf("제출", "마감", "준비", "해", "하세요")
        for (keyword in endKeywords) {
            if (result.contains(keyword)) {
                result = result.substringBefore(keyword)
                break // 하나 찾으면 중단
            }
        }

        // 2. 조사("까지", "전까지") 뒷부분만 남기기 (여기가 중요!)
        // 예: "화요일까지 과제 " -> " 과제 "
        val startKeywords = listOf("까지", "전까지", "부터")
        for (keyword in startKeywords) {
            if (result.contains(keyword)) {
                result = result.substringAfter(keyword)
                break
            }
        }

        // 3. 날짜 관련 단어들이 혹시 남아있다면 지우기 (노이즈 제거)
        result = result
            .replace("다음 주", "")
            .replace("이번 주", "")
            .replace("오늘", "")
            .replace("내일", "")
            .replace(Regex("월요일|화요일|수요일|목요일|금요일|토요일|일요일"), "")
            .trim() // 앞뒤 공백 제거

        return result
    }

    // 화면에 결과를 보여주는 함수
    private fun updateResultUI(data: AssignmentData) {
        if (data.isReady) {
            binding.tvTaskResult.text = "과제: ${data.title}"
            binding.tvDateResult.text = "마감: ${data.dueDate}" // 나중에 보기 좋게 꾸밀 수 있음
            binding.btnAddCalendar.isEnabled = true // 버튼 활성화
        } else {
            binding.tvTaskResult.text = "과제: 분석 실패 (과제명/날짜 불분명)"
            binding.tvDateResult.text = "마감: 분석 실패"
            binding.btnAddCalendar.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { speechRecognizer.destroy() } catch (e: Exception) {}
    }

    // 캘린더 앱을 실행하여 일정을 등록하는 함수


    // 파라미터 이름을 'data'에서 'assignment'로 변경했습니다.
    private fun addToCalendar(assignment: AssignmentData) {
        // 1. 날짜 문자열(yyyyMMdd)을 날짜 객체로 변환
        // 이제 assignment.dueDate로 접근합니다.
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val localDate = LocalDate.parse(assignment.dueDate, formatter)

        // 2. 시간 설정 (오전 9시 ~ 10시)
        val startTime = localDate.atTime(9, 0)
        val endTime = startTime.plusHours(1)

        val startMillis = startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = endTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 3. 인텐트 생성
        val intent = Intent(Intent.ACTION_INSERT).apply {
            // 이제 여기서 data는 확실하게 Intent의 data 속성을 의미합니다.
            data = android.provider.CalendarContract.Events.CONTENT_URI

            // 여기서도 assignment.title로 접근합니다.
            putExtra(android.provider.CalendarContract.Events.TITLE, assignment.title)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "강의 중 자동 추가된 과제입니다.")
        }

        // 4. 실행
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "캘린더 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}




// 데이터 클래스 (파일 맨 아래에 두면 됩니다)
data class AssignmentData(
    val title: String,
    val dueDate: String,
    val isReady: Boolean
)