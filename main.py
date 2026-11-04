import io
import os
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse
from openai import OpenAI
from docx import Document
from pypdf import PdfReader

app = FastAPI(title="ResumeAI API", version="1.0.0")
MAX_BYTES = 10 * 1024 * 1024
ALLOWED = {
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}

def extract_text(filename: str, content: bytes) -> str:
    lower = filename.lower()
    try:
        if lower.endswith(".pdf"):
            reader = PdfReader(io.BytesIO(content))
            return "\n".join(page.extract_text() or "" for page in reader.pages)
        if lower.endswith(".docx"):
            doc = Document(io.BytesIO(content))
            parts = [p.text for p in doc.paragraphs]
            for table in doc.tables:
                for row in table.rows:
                    parts.append(" | ".join(cell.text for cell in row.cells))
            return "\n".join(parts)
    except Exception as exc:
        raise HTTPException(400, f"Could not read resume: {exc}")
    raise HTTPException(415, "Please upload a PDF or DOCX file.")

def make_docx(text: str) -> bytes:
    doc = Document()
    section = doc.sections[0]
    section.top_margin = section.bottom_margin =  Inches(0.55)
    section.left_margin = section.right_margin = Inches(0.7)
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.isupper() and len(line) < 65:
            doc.add_heading(line.title(), level=2)
        elif line.startswith(("- ", "• ", "* ")):
            doc.add_paragraph(line[2:], style="List Bullet")
        else:
            doc.add_paragraph(line)
    out = io.BytesIO()
    doc.save(out)
    return out.getvalue()

from docx.shared import Inches

@app.get("/health")
def health():
    return {"status": "ok", "service": "ResumeAI"}

@app.post("/api/v1/rewrite")
async def rewrite_resume(
    instructions: str = Form(...),
    job_description: str = Form(""),
    resume: UploadFile = File(...),
):
    if not os.getenv("OPENAI_API_KEY"):
        raise HTTPException(503, "ResumeAI service is not configured.")
    if resume.content_type not in ALLOWED and not (resume.filename or "").lower().endswith((".pdf", ".docx")):
        raise HTTPException(415, "Only PDF and DOCX resumes are supported.")
    raw = await resume.read()
    if len(raw) > MAX_BYTES:
        raise HTTPException(413, "Maximum resume size is 10 MB.")
    source_text = extract_text(resume.filename or "resume.pdf", raw)
    if len(source_text.strip()) < 80:
        raise HTTPException(422, "Very little text was found. Scanned PDFs need OCR and are not supported yet.")
    if len(source_text) > 50000:
        source_text = source_text[:50000]

    client = OpenAI()
    prompt = f"""
You are an expert resume editor. Rewrite the resume using the user's instructions.
Return ONLY the complete revised resume text, with clear section headings and concise bullet points.

Critical truthfulness rules:
- Do not invent employers, job titles, dates, degrees, certifications, tools, metrics, achievements, or responsibilities.
- Preserve all factual details from the source unless the user explicitly asks to remove them.
- You may improve wording, structure, clarity, grammar, and ATS readability.
- If a requested detail is missing, do not fabricate it; leave it out or mark it as information the user should supply.
- Do not claim guaranteed ATS scores or job outcomes.
- Use standard headings and simple formatting.

USER INSTRUCTIONS:
{instructions}

TARGET JOB DESCRIPTION (optional):
{job_description or "Not supplied"}

SOURCE RESUME:
{source_text}
"""
    try:
        response = client.responses.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
            input=prompt,
        )
        revised = response.output_text.strip()
    except Exception as exc:
        raise HTTPException(502, f"AI generation failed: {str(exc)[:300]}")
    if len(revised) < 80:
        raise HTTPException(502, "AI returned an incomplete resume. Please try again.")
    docx_bytes = make_docx(revised)
    return StreamingResponse(
        io.BytesIO(docx_bytes),
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        headers={"Content-Disposition": 'attachment; filename="ResumeAI_Updated_CV.docx"'},
    )
