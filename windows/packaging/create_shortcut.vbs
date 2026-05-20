' ============================================================
' Trading Bot v1 - Masaustu Kisayolu Olusturucu
' ============================================================
' Bu .vbs dosyasini cift-tiklayin. Masaustunde "Trading Bot v1"
' adli bir kisayol olusur. Tik = uygulama acilir.
' ============================================================

Set oWS = WScript.CreateObject("WScript.Shell")
Set oFS = WScript.CreateObject("Scripting.FileSystemObject")

' Bu .vbs dosyasinin bulundugu klasor (packaging/)
sScriptDir = oFS.GetParentFolderName(WScript.ScriptFullName)
sProjectRoot = oFS.GetParentFolderName(sScriptDir)
sExe = sProjectRoot & "\dist\TradingBotV1\TradingBotV1.exe"
sIcon = sScriptDir & "\tbv1.ico"

If Not oFS.FileExists(sExe) Then
    MsgBox "TradingBotV1.exe bulunamadi:" & vbCrLf & sExe & vbCrLf & vbCrLf & _
           "Once packaging\build_windows.bat dosyasini calistirmaniz gerek.", _
           vbCritical, "Trading Bot v1 - Kisayol olusturulamadi"
    WScript.Quit 1
End If

sDesktop = oWS.SpecialFolders("Desktop")
sLnk = sDesktop & "\Trading Bot v1.lnk"

Set oLnk = oWS.CreateShortcut(sLnk)
oLnk.TargetPath = sExe
oLnk.WorkingDirectory = oFS.GetParentFolderName(sExe)
oLnk.IconLocation = sIcon
oLnk.Description = "Trading Bot v1 - Indicator Consensus Trading System"
oLnk.WindowStyle = 1
oLnk.Save

MsgBox "Masaustu kisayolu olusturuldu!" & vbCrLf & vbCrLf & _
       "Kisayol: " & sLnk & vbCrLf & _
       "Hedef:   " & sExe, _
       vbInformation, "Trading Bot v1"
