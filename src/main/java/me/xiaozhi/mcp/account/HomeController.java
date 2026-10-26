package me.xiaozhi.mcp.account;
@org.springframework.stereotype.Controller
public class HomeController {
    @org.springframework.web.bind.annotation.GetMapping("/")
    public String home() { return "redirect:/mcp-admin.html"; }
}
